package com.kivan.motoparty.spike

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * RESEARCH §5.2 gotcha: in MODE_NORMAL, does an `AudioTrack` with `USAGE_VOICE_COMMUNICATION`
 * route to the earpiece instead of the connected Bluetooth sink, while `USAGE_MEDIA` stays on
 * the sink? That single fact gates the whole "listener keeps their music" claim.
 *
 * Deliberately passive: the mode is read and never written, `setCommunicationDevice()` is never
 * called, nothing is recorded. Two quiet 1 s tones is the entire footprint.
 */
object UsageTest {

    private const val RATE = 44_100
    private const val TONE_HZ = 440.0
    private const val AMPLITUDE = 0.2
    private const val TONE_MS = 1_000

    fun run(context: Context) {
        SpikeLog.log("=== USAGE test ===")
        val am = context.getSystemService(AudioManager::class.java)

        val mode = am.mode
        SpikeLog.log("usage: AudioManager.mode = ${Names.audioMode(mode)} ($mode) -- not changed by this test")
        if (mode != AudioManager.MODE_NORMAL) {
            SpikeLog.warn("usage: mode is not MODE_NORMAL, the result below does not answer the §5.2 question")
        }

        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            SpikeLog.log(
                "usage: output device ${Names.device(d)} " +
                    "rates=${d.sampleRates.joinToString(",")} sink=${d.isSink}"
            )
        }

        val media = playTone(AudioAttributes.USAGE_MEDIA, "USAGE_MEDIA")
        Thread.sleep(1_000)
        val voice = playTone(AudioAttributes.USAGE_VOICE_COMMUNICATION, "USAGE_VOICE_COMMUNICATION")

        SpikeLog.log("USAGE VERDICT: media -> $media, voice_communication -> $voice")
    }

    /** @return a short description of the device the track was actually routed to. */
    private fun playTone(usage: Int, label: String): String {
        val pcm = ShortArray(RATE * TONE_MS / 1000) { i ->
            // Short fade in/out, so the tone does not click in someone's ear.
            val fade = (i.toDouble() / (RATE * 0.02)).coerceAtMost(1.0)
                .coerceAtMost((pcmLen() - i).toDouble() / (RATE * 0.02))
                .coerceIn(0.0, 1.0)
            (sin(2.0 * PI * TONE_HZ * i / RATE) * AMPLITUDE * fade * 32767.0).toInt().toShort()
        }
        val bytes = Wav.toBytes(pcm)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        return try {
            track.write(bytes, 0, bytes.size)
            track.play()
            SpikeLog.log("usage: $label playing (${TONE_MS} ms, ${TONE_HZ.toInt()} Hz, amplitude $AMPLITUDE)")
            Thread.sleep(300)
            val routed = track.routedDevice
            val desc = if (routed == null) "null (not routed yet)" else Names.device(routed)
            SpikeLog.log("usage: $label getRoutedDevice() = $desc")
            Thread.sleep((TONE_MS - 300).toLong())
            if (routed == null) "unknown" else "${Names.deviceType(routed.type)} \"${routed.productName}\""
        } catch (t: Throwable) {
            SpikeLog.err("usage: $label failed", t)
            "failed: ${t.message}"
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun pcmLen(): Int = RATE * TONE_MS / 1000
}
