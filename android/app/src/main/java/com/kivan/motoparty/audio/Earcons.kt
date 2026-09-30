package com.kivan.motoparty.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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

    const val RATE = 16_000

    /**
     * Tracks still playing. The strong reference matters: the marker callback reaches Java
     * through a weak reference, so a track nobody holds could be collected before its marker and
     * then was never released (no `AudioTrack: stop(..)` line for any earcon in the 2026-09-19
     * bench, two per talk cycle). Unreleased tracks stay registered with the audio server and are
     * a suspect for the open time rising cycle after cycle.
     */
    private val playing: MutableSet<AudioTrack> = ConcurrentHashMap.newKeySet()
    /**
     * Where the marker callback and the release run: never Main ("Main never calls the audio
     * system"; a release ~200 ms after the live beep lands in the middle of a route change).
     */
    private val releaser by lazy { Handler(HandlerThread("earcons").apply { start() }.looper) }

    /** The tone of [kind], synthesised once per process; callers must not write to it. */
    fun pcm(kind: Kind): ShortArray = tones[kind.ordinal]

    private val tones: Array<ShortArray> by lazy { Array(Kind.entries.size) { synth(Kind.entries[it]) } }

    private fun synth(kind: Kind): ShortArray {
        val out = ShortArray(kind.notes.sumOf { (_, ms) -> RATE * ms / 1000 })
        var at = 0
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
                out[at++] = (sin(2 * PI * freq * i / RATE) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    /**
     * The attributes of an earcon. [call]: voice communication, so the tone follows the headset
     * while it is in call mode. Otherwise **media**, never `USAGE_ASSISTANCE_SONIFICATION`: that
     * one plays on `STREAM_SYSTEM`, which the Pixel aliases to the ring stream and **mutes** when
     * touch sounds are off / the ringer is on vibrate (`dumpsys audio`, 2026-09-29: "STREAM_SYSTEM
     * Muted: true", every earcon track `muted … portVolume`). So every non-call earcon — the host-mic
     * talk's LIVE, and every CLOSED, OK and ERROR — was played at volume 0 into the AirPods. Media is
     * the stream the rider has turned up for the music.
     */
    fun usage(call: Boolean): Int =
        if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA

    /**
     * [pcm] with [PREROLL_MS] of silence in front when it goes to the media route: a Bluetooth
     * headset whose A2DP stream was idle (music paused, nothing else playing) starts rendering a
     * little after the stream does, and would swallow the first note. The call route's tone is
     * timed by [LiveCue] and stays as it was.
     */
    fun pcmFor(kind: Kind, call: Boolean): ShortArray = if (call) pcm(kind) else withPreroll[kind.ordinal]

    private val withPreroll: Array<ShortArray> by lazy {
        val pre = RATE * PREROLL_MS / 1000
        Array(Kind.entries.size) { i -> ShortArray(pre + tones[i].size).also { tones[i].copyInto(it, pre) } }
    }

    /**
     * An earcon made ready ahead of time (L9): at most one, taken by the [play] it was made for
     * and by nobody else. Pure, so the hand-over rules are unit-tested without an `AudioTrack`.
     *
     * [put] replaces and returns what was there (the caller releases it); [take] hands the value
     * over only on an exact key match and leaves a different one in place; [discard] empties it.
     * Every method is one atomic step, so the three threads involved (the one that builds, the
     * audio thread that plays, a talk's teardown) can never both get the same value.
     */
    class Slot<K : Any, V : Any> {
        private class Entry<K, V>(val key: K, val value: V)

        private val entry = AtomicReference<Entry<K, V>?>(null)

        fun put(key: K, value: V): V? = entry.getAndSet(Entry(key, value))?.value

        fun take(key: K): V? {
            while (true) {
                val e = entry.get() ?: return null
                if (e.key != key) return null
                if (entry.compareAndSet(e, null)) return e.value
            }
        }

        /** Empties the slot if [value] is still what it holds (a stale timer must not take a newer one). */
        fun discard(value: V): Boolean {
            val e = entry.get() ?: return false
            return e.value === value && entry.compareAndSet(e, null)
        }

        fun discard(): V? = entry.getAndSet(null)?.value

        val isEmpty: Boolean get() = entry.get() == null
    }

    private data class Key(val kind: Kind, val call: Boolean)

    private val prepared = Slot<Key, AudioTrack>()

    /**
     * L9: build the track of an earcon that is about to be wanted — static mode, data written —
     * so that [play] is only `play()`. Called when a talk's engine starts, i.e. while the capture
     * is still waiting for its route; the "live" beep then costs no synthesis, no allocation and
     * no `AudioTrack` construction at the moment the microphone goes live.
     *
     * Returns at once: the track is built on the earcons' own thread (its constructor binders into
     * the audio server, which was seen blocked for over a second during a route change). A [play]
     * that comes first simply builds its own, as before. A prepared track nobody plays is released
     * by [discardPrepared] (the talk ended) or after [PREPARED_TTL_MS].
     */
    fun prepare(kind: Kind, call: Boolean) {
        releaser.post {
            val track = runCatching { build(kind, call) }
                .onFailure { Log.w(TAG, "prepare ${kind.name.lowercase()} failed: $it") }.getOrNull() ?: return@post
            prepared.put(Key(kind, call), track)?.let { runCatching { it.release() } }
            releaser.postDelayed({ if (prepared.discard(track)) runCatching { track.release() } }, PREPARED_TTL_MS)
        }
    }

    /** The talk the prepared earcon was for is over: release it if it was never played. */
    fun discardPrepared() {
        releaser.post { prepared.discard()?.let { runCatching { it.release() } } }
    }

    /** A static track holding [pcmFor], not started. The caller owns it. */
    private fun build(kind: Kind, call: Boolean): AudioTrack {
        val data = pcmFor(kind, call)
        val attrs = AudioAttributes.Builder()
            .setUsage(usage(call))
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
        try {
            track.write(data, 0, data.size)
        } catch (e: Throwable) {
            // A throw after build() must not leave the track registered with the audio server.
            runCatching { track.release() }
            throw e
        }
        return track
    }

    /** Plays on its own static track — the [prepare]d one when there is one; see [usage] and [pcmFor]. */
    fun play(kind: Kind, call: Boolean) {
        val samples = pcmFor(kind, call).size
        val ready = prepared.take(Key(kind, call))
        val track = ready ?: build(kind, call)
        val release = { if (playing.remove(track)) track.release() }
        var started = false
        try {
            playing += track
            track.setNotificationMarkerPosition(samples)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = release()
                override fun onPeriodicNotification(t: AudioTrack) {}
            }, releaser)
            track.play()
            started = true
        } finally {
            // A throw after build() must not leave the track registered with the audio server.
            if (!started) {
                playing.remove(track)
                runCatching { track.release() }
            }
        }
        // F9a's lesson: the phone's own view of the route is not what reaches the ears — so say
        // which device this tone actually went out on. One line per earcon, e.g.
        // `Earcons: closed routed to earpiece`. `none` = the framework had not decided yet.
        // `(prepared)`: the track was built ahead (L9), so this call was only `play()`.
        runCatching {
            Log.i(TAG, "${kind.name.lowercase()} routed to ${ScoRule.describe(track.routedDevice?.type)}" + if (ready != null) " (prepared)" else "")
        }
        // A track re-routed mid-tone may never reach its marker: release it regardless.
        releaser.postDelayed({ release() }, samples * 1000L / RATE + RELEASE_SLACK_MS)
    }

    private const val TAG = "Earcons"
    /** Silence ahead of a media-route earcon (see [pcmFor]). */
    const val PREROLL_MS = 150
    private const val RELEASE_SLACK_MS = 1_000L
    /**
     * How long a prepared earcon waits to be played: past [LiveCue.TIMEOUT_MS] (3.5 s), after
     * which the live beep has been played whatever happened.
     */
    private const val PREPARED_TTL_MS = 6_000L
}
