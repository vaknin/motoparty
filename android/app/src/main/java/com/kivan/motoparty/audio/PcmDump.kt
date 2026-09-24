package com.kivan.motoparty.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The capture loop's own PCM, written to a WAV file for listening to afterwards (Stage A of the
 * wired-mic plan, 2026-09-20). One file per talk, behind a debug setting; nothing here runs unless
 * the rider turned it on.
 *
 * **The one rule that shapes all of it: the `voice-capture` thread is `THREAD_PRIORITY_URGENT_AUDIO`
 * and must never block on I/O, and must not allocate on the frame path either.** So [offer] copies
 * the frame into a buffer taken from a pre-allocated pool, hands it to a bounded queue and returns;
 * a private writer thread does the file work. When that writer falls behind — a slow flush, the
 * card busy — the pool runs empty and frames are **dropped and counted**, never waited for. A dump
 * with holes is a nuisance; a capture loop that misses its deadline is the bug this whole file
 * exists to help find.
 *
 * [close] does not join: it is called from the capture thread's own teardown, which
 * [VoiceEngine.stop] gives 500 ms, and the writer may still have two seconds of queue to drain and
 * a header to rewrite. The thread is a daemon and finishes on its own; [awaitFinished] is for the
 * tests.
 *
 * Pure JVM: no Android class is touched, so all of it is unit-tested against a real file.
 */
class PcmDump(
    private val file: File,
    private val sampleRate: Int,
    /** Samples per [offer]; a frame of any other length is dropped rather than resized. */
    private val frameSamples: Int,
    /** Stop growing the file here. A ride is long and the phone's storage is not. */
    private val maxBytes: Long = MAX_BYTES,
    private val queueFrames: Int = QUEUE_FRAMES,
    /** One line when the file opens and one when it closes; the host sends them to `Hub.log`. */
    private val log: (String) -> Unit = {},
    /**
     * How the writer runs. The default is a daemon thread; the tests pass a runner that never
     * starts it, which is the only way to see the overflow branch deterministically.
     */
    private val runWriter: (Runnable) -> Unit = { r -> thread(name = "pcm-dump", isDaemon = true) { r.run() } },
) {
    /** Empty buffers waiting to be filled, and full ones waiting to be written. */
    private val free = ArrayBlockingQueue<ShortArray>(queueFrames)
    private val filled = ArrayBlockingQueue<ShortArray>(queueFrames)
    private val finished = CountDownLatch(1)

    @Volatile private var running = false

    /** Frames the capture thread could not hand over, i.e. holes in the file. Producer-only. */
    @Volatile var dropped = 0L
        private set

    /** Frames on disk. Written by the writer thread, read after [awaitFinished] by the tests. */
    @Volatile var written = 0L
        private set

    fun start() {
        if (running) return
        repeat(queueFrames) { free.offer(ShortArray(frameSamples)) }
        running = true
        runWriter(Runnable { writeLoop() })
    }

    /**
     * From the capture thread, once per frame, before the encoder sees it. Never blocks, never
     * allocates, never throws.
     */
    fun offer(pcm: ShortArray) {
        if (!running || pcm.size != frameSamples) {
            if (running) dropped++
            return
        }
        val buf = free.poll()
        if (buf == null) {
            dropped++
            return
        }
        System.arraycopy(pcm, 0, buf, 0, frameSamples)
        if (!filled.offer(buf)) {
            free.offer(buf)
            dropped++
        }
    }

    /** The talk is over. Returns at once; the writer drains what is queued and closes the file. */
    fun close() {
        running = false
    }

    /** Tests only: did the writer finish within [timeoutMs]? */
    fun awaitFinished(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun writeLoop() {
        var dataBytes = 0L
        try {
            file.parentFile?.mkdirs()
            log("capture dump: writing ${file.path}")
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(Wav.header(sampleRate, CHANNELS, BITS_PER_SAMPLE, 0))
                val bytes = ByteArray(frameSamples * 2)
                var capped = false
                while (true) {
                    // The poll is what lets [close] be a plain flag: the loop notices within
                    // POLL_MS, and only after the queue has run dry.
                    val buf = filled.poll(POLL_MS, TimeUnit.MILLISECONDS)
                    if (buf == null) {
                        if (running) continue else break
                    }
                    if (dataBytes + bytes.size <= maxBytes) {
                        var i = 0
                        for (v in buf) {
                            val s = v.toInt()
                            bytes[i++] = (s and 0xff).toByte()
                            bytes[i++] = ((s shr 8) and 0xff).toByte()
                        }
                        out.write(bytes)
                        dataBytes += bytes.size
                        written++
                    } else if (!capped) {
                        capped = true
                        log("capture dump: ${file.name} capped at $maxBytes bytes")
                    }
                    free.offer(buf)
                }
                // Last: the length nobody knew when the file was opened.
                out.seek(0)
                out.write(Wav.header(sampleRate, CHANNELS, BITS_PER_SAMPLE, dataBytes))
            }
        } catch (e: Exception) {
            log("capture dump failed: $e")
        } finally {
            running = false
            log(line(dataBytes))
            finished.countDown()
        }
    }

    /**
     * One line per dump, for the bench:
     * `capture dump: capture-20260920-143012.wav, 1193 frames, 23.9 s, 763520 bytes, 0 dropped`.
     * `dropped` is the only number that means something is wrong.
     */
    internal fun line(dataBytes: Long): String {
        val seconds = written * frameSamples.toDouble() / sampleRate
        // Locale.US: the bench parses this line, and a comma decimal separator is not a number to it.
        return "capture dump: ${file.name}, $written frames, ${"%.1f".format(Locale.US, seconds)} s, " +
            "$dataBytes bytes, $dropped dropped"
    }

    companion object {
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        /** 2 s of 20 ms frames: enough to ride out a stalled write, small enough to stay honest. */
        const val QUEUE_FRAMES = 100

        /** 16 kHz mono 16-bit = 32 kB/s, so this is ~20 minutes: longer than any one talk. */
        const val MAX_BYTES = 40L * 1024 * 1024

        /** How long the writer waits for a frame before re-reading the running flag. */
        private const val POLL_MS = 100L
    }
}
