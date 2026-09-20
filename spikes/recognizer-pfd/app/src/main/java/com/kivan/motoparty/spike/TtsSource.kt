package com.kivan.motoparty.spike

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test audio without a microphone: the platform TTS synthesises the phrases to a WAV file,
 * `synthesizeToFile` never touches the speaker, so nothing is audible and no mic is opened.
 */
object TtsSource {

    const val RATE = 16_000

    /** The two phrases fed through the pipe. Second one exists to see whether segmentation fires. */
    const val PHRASE_1 = "motoparty next"
    const val PHRASE_2 = "play album abbey road"

    /**
     * 0.5 s silence | "motoparty next" | 1.5 s silence | "play album abbey road" | 0.5 s silence,
     * at 16 kHz mono PCM16.
     *
     * The leading/trailing silence matters: endpointers treat a session that starts mid-word as
     * noise, and one that ends on a word as still speaking.
     */
    fun buildPrompt(context: Context): ShortArray {
        val tts = openTts(context)
        try {
            val a = synth(tts, context, "utt1", PHRASE_1)
            val b = synth(tts, context, "utt2", PHRASE_2)
            SpikeLog.log(
                "tts: utt1 ${a.sampleRate} Hz ${a.durationMs} ms peak=${Wav.peak(a.samples)}, " +
                    "utt2 ${b.sampleRate} Hz ${b.durationMs} ms peak=${Wav.peak(b.samples)}"
            )
            val a16 = Wav.resample(a, RATE)
            val b16 = Wav.resample(b, RATE)
            val prompt = Wav.concat(
                Wav.silence(RATE, 500),
                a16.samples,
                Wav.silence(RATE, 1500),
                b16.samples,
                Wav.silence(RATE, 500),
            )
            SpikeLog.log(
                "tts: prompt ${prompt.size} samples = ${prompt.size * 1000L / RATE} ms " +
                    "@ $RATE Hz mono PCM16, peak=${Wav.peak(prompt)}"
            )
            return prompt
        } finally {
            runCatching { tts.shutdown() }
        }
    }

    private fun openTts(context: Context): TextToSpeech {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        // Constructed on the main looper: the init callback is posted there.
        val tts = Main.call {
            TextToSpeech(context.applicationContext) { s ->
                status = s
                ready.countDown()
            }
        }
        check(ready.await(15, TimeUnit.SECONDS)) { "TextToSpeech init timed out" }
        check(status == TextToSpeech.SUCCESS) { "TextToSpeech init failed, status=$status" }
        SpikeLog.log("tts: engine=${tts.defaultEngine}")
        val lang = tts.setLanguage(Locale.US)
        SpikeLog.log("tts: setLanguage(en-US) -> $lang (0=OK, 1=fallback, -1=missing, -2=unsupported)")
        check(lang >= TextToSpeech.LANG_AVAILABLE) { "en-US voice not available, code=$lang" }
        return tts
    }

    private fun synth(tts: TextToSpeech, context: Context, id: String, text: String): Pcm {
        val out = File(context.cacheDir, "$id.wav")
        out.delete()
        val done = CountDownLatch(1)
        var failed = false
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) done.countDown()
            }

            @Deprecated("framework requires the override", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) {
                    failed = true
                    done.countDown()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) {
                    SpikeLog.err("tts: synthesizeToFile('$text') error code $errorCode")
                    failed = true
                    done.countDown()
                }
            }
        })
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        val rc = tts.synthesizeToFile(text, params, out, id)
        check(rc == TextToSpeech.SUCCESS) { "synthesizeToFile('$text') returned $rc" }
        check(done.await(20, TimeUnit.SECONDS)) { "synthesizeToFile('$text') timed out" }
        check(!failed) { "synthesizeToFile('$text') reported an error" }
        check(out.length() > 44) { "synthesizeToFile('$text') wrote ${out.length()} bytes" }
        return Wav.parse(out)
    }
}
