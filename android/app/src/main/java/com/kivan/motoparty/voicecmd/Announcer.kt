package com.kivan.motoparty.voicecmd

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.kivan.motoparty.audio.Earcons
import java.util.Locale

/**
 * Speaks confirmations ("Playing X by Y") with an optional earcon in front.
 *
 * [earconPlayer] exists so the tone is not built on Main: the host hands in a lambda that posts
 * to the audio thread (see `LinkHost.earcon`). The default is the direct call, for tests and any
 * caller without an audio thread. Its second argument is `call`, as for [Earcons.play].
 */
class Announcer(
    context: Context,
    private val earconPlayer: (Earcons.Kind, Boolean) -> Unit = { k, call -> Earcons.play(k, call) },
) {
    private var ready = false
    /** Speech in progress, and when the last utterance ended (`elapsedRealtime`). TTS threads write. */
    @Volatile private var speaking = false
    @Volatile private var lastDoneAtMs = 0L
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) Log.w(TAG, "TTS init failed: $status")
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) = done(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = done(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = done(utteranceId)

            private fun done(utteranceId: String?) {
                speaking = false
                lastDoneAtMs = SystemClock.elapsedRealtime()
                utteranceId?.removePrefix(UTTERANCE)?.toLongOrNull()?.let { n -> if (n > lastDoneSeq) lastDoneSeq = n }
            }
        })
    }

    /** How many utterances were asked for so far; the next one is `issued + 1`. Main only. */
    var issued = 0L
        private set
    /** The newest utterance that finished, was stopped or failed (they play in order). */
    @Volatile private var lastDoneSeq = 0L

    /** Has an utterance asked for after the first [after] ended (DIBS.md: the read-back was heard)? */
    fun doneAfter(after: Long): Boolean = lastDoneSeq > after

    /**
     * Is this phone talking, or did it stop less than [ms] ago? In a solo talk every phrase is a
     * command, so a reply the talk microphone hears back must not be taken for the next one.
     */
    fun spokeWithin(ms: Long): Boolean = speaking || SystemClock.elapsedRealtime() - lastDoneAtMs < ms

    /**
     * [call]: speak inside a talk, on the call route the headset is in (voice-communication usage);
     * otherwise on the media route as an assistant reply. [queue]: after what is being said, not
     * instead of it (dibs's lines, DIBS.md).
     */
    fun announce(text: String, earcon: Earcons.Kind?, language: String, call: Boolean = false, queue: Boolean = false) {
        val seq = ++issued
        earcon?.let { earconPlayer(it, call) }
        if (!ready) {
            // Nothing will be said, so nothing is waited for.
            if (seq > lastDoneSeq) lastDoneSeq = seq
            return
        }
        runCatching { tts.language = Locale.forLanguageTag(language) }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Leave room for the earcon before the voice starts.
        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        if (earcon != null) tts.playSilentUtterance(250, mode, null)
        tts.speak(text, if (earcon != null) TextToSpeech.QUEUE_ADD else mode, null, "$UTTERANCE$seq")
    }

    fun release() {
        tts.shutdown()
    }

    companion object {
        private const val TAG = "Announcer"
        private const val UTTERANCE = "announce-"
    }
}
