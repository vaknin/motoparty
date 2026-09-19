package com.kivan.motoparty.voicecmd

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import com.kivan.motoparty.audio.Earcons
import java.util.Locale

/** Speaks confirmations ("Playing X by Y") with an optional earcon in front. */
class Announcer(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) Log.w(TAG, "TTS init failed: $status")
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    fun announce(text: String, earcon: Earcons.Kind?, language: String) {
        earcon?.let { Earcons.play(it, call = false) }
        if (!ready) return
        runCatching { tts.language = Locale.forLanguageTag(language) }
        // Leave room for the earcon before the voice starts.
        if (earcon != null) tts.playSilentUtterance(250, TextToSpeech.QUEUE_FLUSH, null)
        tts.speak(text, if (earcon != null) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "announce")
    }

    fun release() {
        tts.shutdown()
    }

    companion object {
        private const val TAG = "Announcer"
    }
}
