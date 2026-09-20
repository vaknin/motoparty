@file:Suppress("NewApi") // every API-33+ use below sits behind an explicit SDK_INT check

package com.kivan.motoparty.spike

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * THE question (RESEARCH §3.1): does Google's recognizer actually read audio from the
 * `ParcelFileDescriptor` we hand it, or does it silently ignore `EXTRA_AUDIO_SOURCE` and open
 * the microphone?
 *
 * The shape of the test is the shape the real app would use: a pipe, fed at real-time pace from
 * a dedicated thread, ended by closing the write end.
 */
object PfdTest {

    private const val RATE = TtsSource.RATE
    private const val CHUNK_BYTES = 640 // 20 ms of 16 kHz mono PCM16
    private const val CHUNK_NANOS = 20_000_000L
    private const val GRACE_AFTER_EOF_MS = 8_000L

    fun run(context: Context, useDefaultRecognizer: Boolean) {
        SpikeLog.log("=== PFD test (recognizer=${if (useDefaultRecognizer) "default" else "on-device"}) ===")

        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        SpikeLog.log(
            "pfd: RECORD_AUDIO declared in manifest, granted=$granted " +
                "(the app never records; this only tests whether SpeechRecognizer demands it)"
        )
        SpikeLog.log("pfd: isRecognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(context)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpikeLog.log(
                "pfd: isOnDeviceRecognitionAvailable=" +
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            SpikeLog.log("PFD VERDICT: skipped, EXTRA_AUDIO_SOURCE needs API 33 (device is API ${Build.VERSION.SDK_INT})")
            return
        }

        val prompt = try {
            TtsSource.buildPrompt(context)
        } catch (t: Throwable) {
            SpikeLog.err("pfd: could not synthesise test audio", t)
            SpikeLog.log("PFD VERDICT: aborted, no test audio (${t.message})")
            return
        }
        val pcm = Wav.toBytes(prompt)

        val am = context.getSystemService(AudioManager::class.java)
        val clock = SpikeLog.Clock()
        val watch = RecordingWatch(am, clock)

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val state = SessionState()
        state.totalBytes = pcm.size
        val listener = Listener(clock, state)

        var recognizer: SpeechRecognizer? = null
        try {
            recognizer = Main.call {
                if (useDefaultRecognizer) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }
            }
            Main.call { recognizer.setRecognitionListener(listener) }

            val intent = buildIntent(readEnd)
            SpikeLog.log("pfd: extras = ${describeExtras()}")

            watch.start()
            SpikeLog.log("${clock.stamp()} pfd: startListening()")
            Main.call { recognizer.startListening(intent) }

            // Trap 1: the read end stays open here. AOSP marshals the PFD after startListening
            // returns; closing it now gives every session ERROR_CLIENT.
            val writer = startWriter(writeEnd, pcm, clock, state)

            val audioMs = pcm.size * 1000L / (RATE * 2)
            val deadline = audioMs + GRACE_AFTER_EOF_MS + 5_000L
            val ended = state.done.await(deadline, TimeUnit.MILLISECONDS)
            if (!ended) {
                SpikeLog.warn("${clock.stamp()} pfd: no session-ending callback after ${deadline} ms")
            } else {
                // Give trailing callbacks (onResults after onEndOfSegmentedSession) a moment.
                Thread.sleep(1_000)
            }
            writer.interrupt()
        } catch (t: Throwable) {
            SpikeLog.err("pfd: session failed", t)
            state.fatal = t
        } finally {
            watch.stop()
            runCatching { Main.call { recognizer?.destroy() } }
            // Only now is it safe to drop our copy of the read end.
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
        }

        report(state, watch, prompt.size * 1000L / RATE)
    }

    // ---------------------------------------------------------------- intent

    private fun buildIntent(readEnd: ParcelFileDescriptor): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // API 33. The recognizer reads PCM from this descriptor instead of opening the mic.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)

            // API 33. Segmented mode: the session ends when (and only when) the audio closes.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)

            // API 33. Free accuracy for a fixed command vocabulary.
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_BIASING_STRINGS,
                arrayListOf("motoparty", "next", "play album"),
            )
        }

    private fun describeExtras(): String =
        "AUDIO_SOURCE=<pipe read end>, CHANNEL_COUNT=1, ENCODING=ENCODING_PCM_16BIT, " +
            "SAMPLING_RATE=$RATE, SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE, LANGUAGE=en-US, " +
            "PARTIAL_RESULTS=true, BIASING_STRINGS=[motoparty, next, play album]"

    // ---------------------------------------------------------------- writer

    /**
     * Trap 2: a dedicated thread, paced by a monotonic clock, 20 ms at a time. In the real app
     * this thread must never be the capture thread — the pipe holds 64 KiB and a blocked write
     * would stall capture.
     *
     * That 64 KiB is also the tell: if the recognizer never reads the pipe, writes start blocking
     * after ~2 s of audio. A large `maxWriteMs` therefore means "nobody drained the pipe".
     */
    private fun startWriter(
        writeEnd: ParcelFileDescriptor,
        pcm: ByteArray,
        clock: SpikeLog.Clock,
        state: SessionState,
    ): Thread {
        val t = Thread({
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
            val t0 = System.nanoTime()
            var off = 0
            var chunks = 0
            try {
                while (off < pcm.size && !Thread.currentThread().isInterrupted) {
                    val len = minOf(CHUNK_BYTES, pcm.size - off)
                    val before = System.nanoTime()
                    out.write(pcm, off, len)
                    out.flush()
                    val writeMs = (System.nanoTime() - before) / 1_000_000L
                    if (writeMs > state.maxWriteMs) state.maxWriteMs = writeMs
                    if (writeMs > 100) {
                        SpikeLog.warn(
                            "${clock.stamp()} pfd: write of $len bytes blocked ${writeMs} ms at " +
                                "offset $off -- pipe not being drained"
                        )
                    }
                    off += len
                    chunks++
                    state.bytesWritten = off
                    val due = t0 + chunks * CHUNK_NANOS
                    val sleep = due - System.nanoTime()
                    if (sleep > 0) LockSupport.parkNanos(sleep)
                }
                SpikeLog.log(
                    "${clock.stamp()} pfd: wrote $off/${pcm.size} bytes in $chunks chunks " +
                        "(maxWrite=${state.maxWriteMs} ms); closing write end = EOF"
                )
            } catch (t: Throwable) {
                SpikeLog.err("${clock.stamp()} pfd: writer failed at offset $off", t)
                state.writerError = t
            } finally {
                // Trap 3: the session ends with EOF, not stopListening().
                runCatching { out.close() }
                state.eofAtMs = clock.ms()
            }
        }, "pfd-writer")
        t.isDaemon = true
        t.start()
        return t
    }

    // ---------------------------------------------------------------- results

    private class SessionState {
        val done = CountDownLatch(1)
        val segments = mutableListOf<String>()
        var finalText: String? = null
        var lastPartial: String? = null
        var errorCode: Int? = null
        var segmentedEnd = false
        var readyForSpeech = false
        var beginningOfSpeech = false

        @Volatile var bytesWritten = 0
        @Volatile var totalBytes = 0
        @Volatile var maxWriteMs = 0L
        @Volatile var eofAtMs = -1L
        var writerError: Throwable? = null
        var fatal: Throwable? = null
    }

    private class Listener(
        private val clock: SpikeLog.Clock,
        private val state: SessionState,
    ) : RecognitionListener {

        private fun best(results: Bundle?): String? =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        override fun onReadyForSpeech(params: Bundle?) {
            state.readyForSpeech = true
            SpikeLog.log("${clock.stamp()} cb onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            state.beginningOfSpeech = true
            SpikeLog.log("${clock.stamp()} cb onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit // far too chatty to log

        override fun onBufferReceived(buffer: ByteArray?) {
            SpikeLog.log("${clock.stamp()} cb onBufferReceived ${buffer?.size ?: 0} bytes")
        }

        override fun onEndOfSpeech() {
            SpikeLog.log("${clock.stamp()} cb onEndOfSpeech")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = best(partialResults)
            state.lastPartial = text ?: state.lastPartial
            SpikeLog.log("${clock.stamp()} cb onPartialResults: \"${text ?: ""}\"")
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = best(segmentResults)
            if (text != null) state.segments += text
            SpikeLog.log("${clock.stamp()} cb onSegmentResults: \"${text ?: ""}\"")
        }

        override fun onEndOfSegmentedSession() {
            state.segmentedEnd = true
            SpikeLog.log("${clock.stamp()} cb onEndOfSegmentedSession")
            state.done.countDown()
        }

        override fun onResults(results: Bundle?) {
            val text = best(results)
            state.finalText = text ?: state.finalText
            SpikeLog.log("${clock.stamp()} cb onResults: \"${text ?: ""}\"")
            // In a segmented session onResults is not the end; if the recognizer ignored
            // segmentation it is, so release the wait either way after a beat.
            Main.handler.postDelayed({ state.done.countDown() }, 1_500)
        }

        override fun onError(error: Int) {
            state.errorCode = error
            SpikeLog.err("${clock.stamp()} cb onError: ${Names.error(error)} ($error)")
            state.done.countDown()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            SpikeLog.log("${clock.stamp()} cb onEvent $eventType")
        }

        override fun onLanguageDetection(results: Bundle) {
            SpikeLog.log("${clock.stamp()} cb onLanguageDetection")
        }
    }

    private fun report(state: SessionState, watch: RecordingWatch, audioMs: Long) {
        val heard = when {
            state.segments.isNotEmpty() -> state.segments.joinToString(" | ")
            state.finalText != null -> state.finalText!!
            state.lastPartial != null -> "(partial only) ${state.lastPartial}"
            else -> null
        }

        // A write that is *still* blocked never updates maxWriteMs, so an incomplete byte count
        // is the other half of the "nobody read the pipe" signal.
        val incomplete = state.bytesWritten < state.totalBytes
        val notDrained = incomplete || state.maxWriteMs >= 500

        SpikeLog.log(
            "PFD PIPE: audio=${audioMs} ms, wrote=${state.bytesWritten}/${state.totalBytes} bytes, " +
                "maxWriteBlock=${state.maxWriteMs} ms, eofAt=${state.eofAtMs} ms, " +
                "drained=${!notDrained}"
        )
        SpikeLog.log(
            "PFD CALLBACKS: onReadyForSpeech=${state.readyForSpeech}, " +
                "onBeginningOfSpeech=${state.beginningOfSpeech}, " +
                "segments=${state.segments.size}, onEndOfSegmentedSession=${state.segmentedEnd}, " +
                "micOpened=${watch.micWasOpened()}"
        )

        val verdict = when {
            watch.micWasOpened() ->
                "mic opened instead" +
                    (if (heard != null) " (and heard \"$heard\")" else "")
            state.fatal != null -> "aborted: ${state.fatal!!.message}"
            heard != null -> heard
            state.errorCode != null -> "error ${state.errorCode} ${Names.error(state.errorCode!!)}"
            notDrained ->
                "no result; pipe was never drained (wrote ${state.bytesWritten}/${state.totalBytes} bytes, " +
                    "longest blocked write ${state.maxWriteMs} ms)"
            else -> "no result and no error (recognizer stayed silent)"
        }
        SpikeLog.log("PFD VERDICT: $verdict")
    }
}
