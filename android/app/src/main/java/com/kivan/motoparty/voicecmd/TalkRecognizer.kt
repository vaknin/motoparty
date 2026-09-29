package com.kivan.motoparty.voicecmd

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.kivan.motoparty.audio.PcmTee
import com.kivan.motoparty.audio.VoiceEngine

/**
 * Speech recognition on the talk's own microphone, for PROTOCOL.md "Commands" (option A,
 * 2026-09-29): while a talk is open, every phrase the rider says is transcribed and handed to
 * [onPhrase]; the host decides whether it is a command (the wake word) or conversation.
 *
 * The recognizer never opens a microphone. The voice engine's capture loop tees each raw frame
 * into a [PcmTee] ([attach]), whose writer thread writes it into a pipe; the read end goes to
 * Google's recognizer as `EXTRA_AUDIO_SOURCE` with `EXTRA_SEGMENTED_SESSION`, which gives one
 * `onSegmentResults` per phrase for as long as the pipe stays open. Proven on the Pixel 8 by
 * `spikes/recognizer-pfd` (no RECORD_AUDIO needed; the wake word comes back as "Moto party"). The
 * extras need API 33: below that, talk runs without in-talk commands (logged once).
 *
 * Closing the write end is the end of the session (EOF); the recognizer is destroyed when it says
 * so, or after [GRACE_MS]. Nothing that happens here may affect the talk: every failure is logged
 * and, while the talk lasts, answered by a fresh attempt — on-device first, the default service
 * when on-device refuses (as the one-shot recognizer used to).
 *
 * Main thread only: `SpeechRecognizer` is a Main-thread API, and so is the host.
 */
class TalkRecognizer(
    private val context: Context,
    /** Hand the voice engine its tee (null = stop teeing). Main. */
    private val attach: (PcmTee?) -> Unit,
    /** One recognised phrase of talk [session]; may arrive shortly after [stop] (the last one). */
    private val onPhrase: (session: Int, text: String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    /** The talk being listened to; 0 = none. */
    private var session = 0
    private var language = "en-US"
    private var attempt: Attempt? = null
    private var useDefault = false
    private var quickFailures = 0
    private var warnedOld = false

    /** The talk [session]'s capture is up: start listening to it (once per session). */
    fun start(session: Int, language: String) {
        if (this.session == session) return
        stop()
        if (Build.VERSION.SDK_INT < 33) {
            if (!warnedOld) log("talk recognizer: needs Android 13 (API ${Build.VERSION.SDK_INT}); talks run without spoken commands")
            warnedOld = true
            return
        }
        this.session = session
        this.language = language
        useDefault = false
        quickFailures = 0
        begin()
    }

    /** The talk closed: EOF to the recognizer. Safe to call when nothing runs. */
    fun stop() {
        session = 0
        main.removeCallbacksAndMessages(RETRY)
        attempt?.let {
            attempt = null
            attach(null)
            // An attempt only ever exists on API 33+ (see [start]).
            if (Build.VERSION.SDK_INT >= 33) it.end()
        }
    }

    @RequiresApi(33)
    private fun begin() {
        val onDevice = !useDefault && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            log("talk recognizer: no recognition service; talks run without spoken commands")
            return
        }
        val a = try {
            Attempt(session, onDevice)
        } catch (e: Exception) {
            log("talk recognizer: could not start: $e")
            return
        }
        attempt = a
        attach(a.tee)
        log("talk recognizer: listening (${if (onDevice) "on-device" else "default service"}, session $session)")
    }

    /** [a] ended on its own (an error, or the recognizer closed the session). */
    @RequiresApi(33)
    private fun ended(a: Attempt, error: Int?) {
        if (attempt !== a) return // already stopped or replaced; nothing to decide
        attempt = null
        attach(null)
        a.destroy()
        if (a.session != session) return
        if (error != null && a.onDevice && error in FALLBACK_ERRORS) {
            log("talk recognizer: on-device refused (${errorName(error)}); trying the default service")
            useDefault = true
            begin()
            return
        }
        // A long session that ended is normal (the default service may not segment); a string of
        // quick failures is a recognizer that will not work this talk.
        quickFailures = if (SystemClock.elapsedRealtime() - a.startedAtMs >= QUICK_MS) 0 else quickFailures + 1
        if (quickFailures >= MAX_QUICK_FAILURES) {
            log("talk recognizer: failed $quickFailures times in a row; no spoken commands for this talk")
            return
        }
        main.postAtTime({ if (session == a.session && attempt == null) begin() }, RETRY, SystemClock.uptimeMillis() + RETRY_MS)
    }

    /** One recognizer + one pipe + one tee. Created and ended on Main. */
    @RequiresApi(33)
    private inner class Attempt(val session: Int, val onDevice: Boolean) : RecognitionListener {
        val startedAtMs = SystemClock.elapsedRealtime()
        private val readEnd: ParcelFileDescriptor
        val tee: PcmTee
        private lateinit var recognizer: SpeechRecognizer
        private var segments = 0
        private var destroyed = false

        init {
            val pipe = ParcelFileDescriptor.createPipe()
            readEnd = pipe[0]
            tee = PcmTee(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]), VoiceEngine.FRAME, log = log)
            try {
                recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
                recognizer.setRecognitionListener(this)
                recognizer.startListening(intent(readEnd))
                // Only now: the writer may block on the pipe, and the read end must stay open
                // until destroy (the spike's trap 1: closing it early is ERROR_CLIENT).
                tee.start()
            } catch (e: Exception) {
                destroy()
                runCatching { pipe[1].close() }
                throw e
            }
        }

        /** EOF now; destroy when the recognizer confirms, or after [GRACE_MS]. */
        fun end() {
            tee.close()
            main.postDelayed({ destroy() }, GRACE_MS)
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            tee.close()
            runCatching { recognizer.destroy() }
            runCatching { readEnd.close() }
            log("talk recognizer: done, $segments phrase(s), ${tee.line()}")
        }

        private fun phrase(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) return
            segments++
            onPhrase(session, text)
        }

        override fun onSegmentResults(segmentResults: Bundle) = phrase(segmentResults)

        override fun onEndOfSegmentedSession() {
            if (attempt === this) ended(this, null) else destroy()
        }

        override fun onResults(results: Bundle?) {
            // Segmented sessions end with onEndOfSegmentedSession; a service that ignored the
            // segmentation extra delivers its one phrase here and is done.
            if (segments == 0) phrase(results)
            if (attempt === this) ended(this, null) else destroy()
        }

        override fun onError(error: Int) {
            if (attempt === this) {
                log("talk recognizer: ${errorName(error)} ($error)")
                ended(this, error)
            } else {
                destroy()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** The spike's extras (`spikes/recognizer-pfd/.../PfdTest.kt`), minus partial results. */
    @RequiresApi(33)
    private fun intent(readEnd: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceEngine.RATE)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, BIASING)
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot check support"
        else -> "error"
    }

    private companion object {
        /** How long a closed session may take to deliver its last phrase before it is destroyed. */
        const val GRACE_MS = 3_000L
        const val RETRY_MS = 500L
        const val QUICK_MS = 2_000L
        const val MAX_QUICK_FAILURES = 3
        /** Token for the pending retry, so [stop] can cancel it. */
        val RETRY = Any()
        val BIASING = arrayListOf(
            "motoparty", "moto party", "play", "pause", "resume", "next", "previous", "skip",
            "volume up", "volume down", "over", "end talk", "hang up",
        )
        val FALLBACK_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )
    }
}
