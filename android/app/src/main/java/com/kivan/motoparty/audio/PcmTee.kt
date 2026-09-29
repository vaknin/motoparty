package com.kivan.motoparty.audio

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The talk's captured PCM, teed to [out] as 16-bit little-endian mono — in the app the write end
 * of the pipe the in-talk recognizer reads ([com.kivan.motoparty.voicecmd.TalkRecognizer]).
 *
 * Same rule as [PcmDump], for the same thread: [offer] runs on `voice-capture`
 * (`THREAD_PRIORITY_URGENT_AUDIO`) before the encoder, so it never blocks, never allocates and
 * never throws. It copies the frame into a pooled buffer and queues it; a private writer thread
 * does the (blocking) pipe writes. A pipe holds 64 KiB, about 2 s of audio: a recognizer that
 * stops reading blocks only the writer, the pool runs dry and frames are **dropped and counted**.
 * Commands with holes are a nuisance; a talk that stutters because the recognizer stalled is not
 * acceptable.
 *
 * [close] returns at once; the writer drains what is queued and then closes [out], which is the
 * recognizer's EOF. Pure JVM, unit-tested.
 */
class PcmTee(
    private val out: OutputStream,
    private val frameSamples: Int,
    private val queueFrames: Int = QUEUE_FRAMES,
    private val log: (String) -> Unit = {},
    /** How the writer runs; tests pass one that never starts it, to see the overflow branch. */
    private val runWriter: (Runnable) -> Unit = { r -> thread(name = "pcm-tee", isDaemon = true) { r.run() } },
) {
    private val free = ArrayBlockingQueue<ShortArray>(queueFrames)
    private val filled = ArrayBlockingQueue<ShortArray>(queueFrames)
    private val finished = CountDownLatch(1)

    @Volatile private var running = false

    /** Frames the capture thread could not hand over. Producer-only. */
    @Volatile var dropped = 0L
        private set

    /** Frames written to [out]. Writer-only. */
    @Volatile var written = 0L
        private set

    fun start() {
        if (running) return
        repeat(queueFrames) { free.offer(ShortArray(frameSamples)) }
        running = true
        runWriter(Runnable { writeLoop() })
    }

    /** From the capture thread, once per frame. Never blocks, never allocates, never throws. */
    fun offer(pcm: ShortArray) {
        if (!running) return
        if (pcm.size != frameSamples) {
            dropped++
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

    /** Stop taking frames. The writer drains the queue, then closes [out] (EOF). */
    fun close() {
        running = false
    }

    /** Tests only. */
    fun awaitFinished(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun writeLoop() {
        val bytes = ByteArray(frameSamples * 2)
        try {
            while (true) {
                val buf = filled.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (buf == null) {
                    if (running) continue else break
                }
                var i = 0
                for (v in buf) {
                    val s = v.toInt()
                    bytes[i++] = (s and 0xff).toByte()
                    bytes[i++] = ((s shr 8) and 0xff).toByte()
                }
                free.offer(buf)
                out.write(bytes)
                written++
            }
        } catch (e: IOException) {
            // The reader went away (the recognizer was destroyed, or failed): nothing to feed.
            running = false
            log("pcm tee: pipe closed after $written frames: ${e.message}")
        } finally {
            runCatching { out.close() }
            finished.countDown()
        }
    }

    /** `pcm tee: N frames, N dropped` — `dropped` above 0 means the recognizer fell behind. */
    fun line(): String = "pcm tee: $written frames, $dropped dropped"

    companion object {
        /** 2 s of 20 ms frames, as [PcmDump]. */
        const val QUEUE_FRAMES = 100

        private const val POLL_MS = 100L
    }
}
