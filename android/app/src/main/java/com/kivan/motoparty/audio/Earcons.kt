package com.kivan.motoparty.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Short generated tones. No assets: every earcon is a list of (frequency Hz, duration ms). */
object Earcons {
    enum class Kind(val notes: List<Pair<Int, Int>>) {
        LIVE(listOf(880 to 90, 1320 to 110)),
        CLOSED(listOf(1320 to 90, 880 to 110)),
        OK(listOf(660 to 80, 990 to 120)),
        ERROR(listOf(330 to 160, 0 to 60, 330 to 160)),
        LISTEN(listOf(1175 to 120)),
    }

    private const val RATE = 16_000

    fun pcm(kind: Kind): ShortArray {
        val out = ArrayList<Short>()
        for ((freq, ms) in kind.notes) {
            val n = RATE * ms / 1000
            val fade = min(n / 4, RATE * 8 / 1000)
            for (i in 0 until n) {
                val env = when {
                    freq == 0 -> 0.0
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                out += (sin(2 * PI * freq * i / RATE) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    /**
     * Plays on its own static track. [call] selects the voice-communication usage so the tone
     * follows the headset while it is in call mode; otherwise it is a sonification sound.
     */
    fun play(kind: Kind, call: Boolean) {
        val data = pcm(kind)
        val attrs = AudioAttributes.Builder()
            .setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        track.write(data, 0, data.size)
        track.setNotificationMarkerPosition(data.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
    }
}
