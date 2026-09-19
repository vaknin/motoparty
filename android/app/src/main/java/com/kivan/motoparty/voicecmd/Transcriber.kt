package com.kivan.motoparty.voicecmd

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One utterance -> text. Prefers the on-device recognizer (no coverage needed on the road),
 * falls back to the default service if on-device is missing or rejects the language.
 * Main thread only.
 */
class Transcriber(private val context: Context) {
    sealed interface Result {
        data class Text(val text: String) : Result
        data class Failed(val error: Int) : Result
    }

    val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun listen(language: String): Result {
        if (onDeviceAvailable) {
            val r = attempt(language, onDevice = true)
            if (r !is Result.Failed || r.error !in FALLBACK_ERRORS) return r
            Log.i(TAG, "on-device recognizer failed (${r.error}); trying the default service")
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return Result.Failed(SpeechRecognizer.ERROR_CLIENT)
        return attempt(language, onDevice = false)
    }

    private suspend fun attempt(language: String, onDevice: Boolean): Result {
        val recognizer = if (onDevice && Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        try {
            return withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle) {
                            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (cont.isActive) cont.resume(if (text.isNullOrBlank()) Result.Failed(SpeechRecognizer.ERROR_NO_MATCH) else Result.Text(text))
                        }

                        override fun onError(error: Int) {
                            if (cont.isActive) cont.resume(Result.Failed(error))
                        }

                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    recognizer.startListening(intent)
                    cont.invokeOnCancellation { recognizer.cancel() }
                }
            } ?: Result.Failed(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        } finally {
            recognizer.destroy()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 12_000L
        private const val TAG = "Transcriber"
        private val FALLBACK_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )
    }
}
