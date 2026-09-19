package com.kivan.motoparty.music

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The part of [Player] that [SyncController] drives. Split out so the timing logic can be tested
 * on the JVM against a fake, without ExoPlayer.
 */
interface PlayerControls {
    val loadedId: String?
    val isReady: Boolean
    val isPlaying: Boolean
    val positionMs: Long
    var speed: Float
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

/** The shared timeline: at host time [atHostTimeMs] the track is at [positionMs] (PROTOCOL.md "Music flow"). */
data class Anchor(val id: String, val positionMs: Long, val atHostTimeMs: Long, val playing: Boolean) {
    fun expectedAt(hostNowMs: Long): Long = if (playing) positionMs + (hostNowMs - atHostTimeMs) else positionMs
}

/**
 * Keeps the local [Player] on the [Anchor]: scheduled start, drift check every 10 s (2 s after a
 * start or correction), per-phone latency trim (positive = play ahead, for a laggier headset).
 *
 * Measured on the Pixel 8 with AirPods Pro (A2DP): the audible position starts moving ~400 ms
 * after play() or a seek, and up to ~2 s when the Bluetooth stream was idle. So:
 * - play() is issued early by a learned [startLatencyMs];
 * - drift of 80 ms..1 s is absorbed by briefly playing up to 5 % faster/slower (pitch kept),
 *   because a re-seek would restart the output and create a new lag of its own;
 * - only drift above 1 s re-seeks (with the learned lead).
 * Main thread only.
 */
class SyncController(
    private val player: PlayerControls,
    private val scope: CoroutineScope,
    private val hostNow: () -> Long,
    private val trimMs: () -> Int,
) {
    var anchor: Anchor? = null
        private set
    /** Player position minus expected position at the last check, for the UI. */
    var lastDriftMs: Long? = null
        private set
    var startLatencyMs = DEFAULT_START_LATENCY_MS
        private set

    private var job: Job? = null
    private var held = false

    fun apply(a: Anchor?) {
        anchor = a
        cancelJob()
        if (a == null) {
            player.pause()
            return
        }
        if (!a.playing || held) {
            player.pause()
            player.seekTo(a.positionMs)
            return
        }
        job = scope.launch {
            // Seek while paused, give the seek time to finish, then play() early by the start-up
            // latency. If the anchor is too close (or past, when joining mid-track), start at a
            // later point on the same timeline.
            val lead = startLatencyMs
            val startAt = maxOf(a.atHostTimeMs, hostNow() + lead + PREPARE_MS)
            player.pause()
            player.seekTo(a.expectedAt(startAt) + trimMs())
            delay(startAt - lead - hostNow())
            player.play()
            delay(EARLY_CHECK_MS)
            check(learnFromLead = lead)
        }
    }

    /** Stop local output without changing the shared timeline (e.g. while dictating). */
    fun hold() {
        held = true
        cancelJob()
        player.pause()
    }

    /** Rejoin the timeline after [hold]. */
    fun release() {
        held = false
        apply(anchor)
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
        player.speed = 1f
    }

    /** Drift checks for as long as this anchor plays; each check decides when the next one is. */
    private suspend fun check(learnFromLead: Long?) {
        var learn = learnFromLead
        while (true) {
            val (afterMs, nextLearn) = checkOnce(learn) ?: return
            delay(afterMs)
            learn = nextLearn
        }
    }

    /** One drift check; returns (delay until the next check, lead to learn from) or null to stop. */
    private suspend fun checkOnce(learnFromLead: Long?): Pair<Long, Long?>? {
        val a = anchor ?: return null
        if (!a.playing || held) return null
        if (player.loadedId != a.id || !player.isReady || !player.isPlaying) return DRIFT_CHECK_MS to null
        val now = hostNow()
        val expected = a.expectedAt(now) + trimMs()
        val drift = player.positionMs - expected
        lastDriftMs = drift
        if (learnFromLead != null && abs(drift) < SEEK_ABOVE_MS) {
            // `lead - drift` is a direct estimate of the output delay this start actually had.
            // Damped, not averaged: a straight mean of two (weight 1/2) overshoots, because the
            // cold-A2DP start and the warm resume have genuinely different lags. Measured on the
            // Pixel 8 with AirPods, weight 1/2 rang -693 -> +428 -> +173 -> +93 ms, and each of
            // those corrections is a multi-second rate nudge.
            val measured = (learnFromLead - drift).coerceIn(0, MAX_START_LATENCY_MS)
            startLatencyMs += (measured - startLatencyMs) / LEARN_DIVISOR
        }
        return when {
            abs(drift) <= RESYNC_MS -> {
                Log.i(TAG, "drift $drift ms (start latency $startLatencyMs ms)")
                DRIFT_CHECK_MS to null
            }
            abs(drift) > SEEK_ABOVE_MS -> {
                Log.i(TAG, "drift $drift ms: seeking (lead $startLatencyMs ms)")
                player.seekTo(expected + startLatencyMs)
                EARLY_CHECK_MS to startLatencyMs
            }
            else -> {
                val speed = (1f - drift.toFloat() / NUDGE_WINDOW_MS).coerceIn(1f - MAX_NUDGE, 1f + MAX_NUDGE)
                val durationMs = (abs(drift) / abs(speed - 1f)).toLong()
                Log.i(TAG, "drift $drift ms: speed $speed for $durationMs ms")
                player.speed = speed
                try {
                    delay(durationMs)
                } finally {
                    player.speed = 1f
                }
                EARLY_CHECK_MS to null
            }
        }
    }

    companion object {
        const val DRIFT_CHECK_MS = 10_000L
        const val EARLY_CHECK_MS = 2_000L
        /** PROTOCOL.md: correct when off by more than 80 ms. */
        const val RESYNC_MS = 80L
        private const val SEEK_ABOVE_MS = 1_000L
        private const val PREPARE_MS = 250L
        private const val NUDGE_WINDOW_MS = 4_000f
        private const val MAX_NUDGE = 0.05f
        const val DEFAULT_START_LATENCY_MS = 300L
        /** How hard one measurement pulls the learned start latency (1/4 of the way). */
        const val LEARN_DIVISOR = 4
        private const val MAX_START_LATENCY_MS = 1_000L
        private const val TAG = "SyncController"
    }
}
