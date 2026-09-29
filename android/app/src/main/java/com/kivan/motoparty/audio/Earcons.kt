package com.kivan.motoparty.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
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
    }

    private const val RATE = 16_000

    /**
     * Tracks still playing. The strong reference matters: the marker callback reaches Java
     * through a weak reference, so a track nobody holds could be collected before its marker and
     * then was never released (no `AudioTrack: stop(..)` line for any earcon in the 2026-09-19
     * bench, two per talk cycle). Unreleased tracks stay registered with the audio server and are
     * a suspect for the open time rising cycle after cycle.
     */
    private val playing: MutableSet<AudioTrack> = ConcurrentHashMap.newKeySet()
    private val main by lazy { Handler(Looper.getMainLooper()) }

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
        playing += track
        val release = { if (playing.remove(track)) track.release() }
        track.setNotificationMarkerPosition(data.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = release()
            override fun onPeriodicNotification(t: AudioTrack) {}
        }, main)
        track.play()
        // F9a's lesson: the phone's own view of the route is not what reaches the ears — so say
        // which device this tone actually went out on. One line per earcon, e.g.
        // `Earcons: closed routed to earpiece`. `none` = the framework had not decided yet.
        runCatching {
            Log.i(TAG, "${kind.name.lowercase()} routed to ${ScoRule.describe(track.routedDevice?.type)}")
        }
        // A track re-routed mid-tone may never reach its marker: release it regardless.
        main.postDelayed({ release() }, data.size * 1000L / RATE + RELEASE_SLACK_MS)
    }

    private const val TAG = "Earcons"
    private const val RELEASE_SLACK_MS = 1_000L
}
