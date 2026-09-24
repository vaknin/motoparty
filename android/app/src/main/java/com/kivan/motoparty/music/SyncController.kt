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

    /**
     * Extra `, key value` pairs for [SyncController]'s `trace:` lines (diagnostics only, never
     * read by any decision), e.g. whether the output is really playing.
     */
    val traceInfo: String get() = ""
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
 * - drift of 80 ms..1 s is absorbed by playing up to 2 % faster/slower (pitch kept) for ~10 s
 *   or longer — slow on purpose, so the time-stretch stays inaudible;
 *   because a re-seek would restart the output and create a new lag of its own;
 * - only drift above 1 s re-seeks (with the learned lead).
 *
 * Every drift figure is a *filtered reading*: the median of [FILTER_SAMPLES] samples of
 * position − expected, [FILTER_EVERY_MS] apart ([FILTER_SPAN_MS] in all). On A2DP the raw
 * ExoPlayer position flips between two levels ~180–250 ms apart at speed 1.0 with no seek (the
 * D2 bench of 2026-09-19; probably Media3's AudioTrackPositionTracker switching between the
 * AudioTimestamp and the head-position − latency paths), and one raw reading taken on the wrong
 * level made a nudge of its own and scattered the landings ±200 ms. On top of that a 80 ms..1 s
 * error is corrected only once two consecutive filtered readings agree ([CONFIRM_WITHIN_MS], same
 * sign); a single one only makes the next reading the confirming one. Above 1 s one filtered
 * reading is enough: a flip moves the median by ~250 ms at most, so that error is real, and waiting
 * 2 s more to confirm it is audible.
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
    /** Player position minus expected position at the last check (the filtered reading), for the UI. */
    var lastDriftMs: Long? = null
        private set
    var startLatencyMs = DEFAULT_START_LATENCY_MS
        private set

    /**
     * The same estimate for a start whose output route was just rebuilt (the resume after talk:
     * the headset has only just gone back from HFP to A2DP and the stream is idle). That start is
     * later than a warm one, so learning both into one number makes warm starts overshoot and
     * cold ones undershoot — the sign-flipping -693 -> +428 -> +173 -> +93 ms seen on the bench.
     *
     * It starts at the *warm* default rather than at a guessed larger value: seeding it at 700 ms
     * was measured (2026-09-19, Bluetooth off, so the route is the phone's own speaker and the
     * resume is not actually cold) to start the resume 335 ms ahead of the timeline, which then
     * rang +335 -> -92 -> +194 for half a minute. An estimate that is only ever learned cannot
     * be wrong about a route it has not seen.
     */
    var coldStartLatencyMs = DEFAULT_START_LATENCY_MS
        private set

    private var job: Job? = null

    /**
     * Where the drift trace goes; null turns it off. Logging only: the trace reads the player and
     * the clock and never changes a decision (`SyncControllerTest` runs the same script with and
     * without it and compares every call on the player).
     *
     * Why it exists: on the 2026-09-19 device run every speed-UP nudge ended ~+150..+220 ms ahead
     * whatever its size, every slow-down landed within 20–30 ms, and the reading also dropped
     * 140–220 ms between checks with no speed change. That does not scale with the nudge, so the
     * suspicion is a position reading that steps (A2DP delay report) rather than a rate error.
     * One `trace:` line a second around each start and nudge lets one device run decide.
     */
    var traceLog: ((String) -> Unit)? = { Log.i(TAG, it) }

    /**
     * Where the decision lines go (`start …`, `drift …`, `check: …`, `nudge done: …`); the bench
     * parser reads them from logcat. Replaceable so the tests can read them too.
     */
    var log: (String) -> Unit = { Log.i(TAG, it) }
    private var traceJob: Job? = null
    /** Host time at which the running trace stops. */
    private var traceUntilMs = 0L

    /** Depth, not a flag: talk and the recognizer can hold at the same time. */
    private var holds = 0
    private val held: Boolean get() = holds > 0

    /**
     * Put the player on [a]. [cold] marks a start whose audio route has just changed, so it uses
     * (and learns) [coldStartLatencyMs] instead of [startLatencyMs].
     */
    fun apply(a: Anchor?, cold: Boolean = false) {
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
            val lead = if (cold) coldStartLatencyMs else startLatencyMs
            val startAt = maxOf(a.atHostTimeMs, hostNow() + lead + PREPARE_MS)
            player.pause()
            player.seekTo(a.expectedAt(startAt) + trimMs())
            delay(startAt - lead - hostNow())
            log("start ${if (cold) "cold" else "warm"}: lead $lead ms, ${startAt - hostNow()} ms to the anchor point")
            player.play()
            traceFor(TRACE_AFTER_MS, if (cold) "start-cold" else "start-warm")
            delay(EARLY_CHECK_MS)
            check(Learn(lead, cold))
        }
    }

    /**
     * Stop local output without changing the shared timeline (while dictating, and while the
     * headset goes back to A2DP after talk). Nested: [release] rejoins on the last one.
     */
    fun hold() {
        holds++
        cancelJob()
        player.pause()
    }

    /** Rejoin the timeline after [hold]. [cold] as in [apply]: the route changed under us. */
    fun release(cold: Boolean = false) {
        if (holds == 0) return
        if (--holds > 0) return
        apply(anchor, cold)
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
        traceJob?.cancel()
        traceJob = null
        traceUntilMs = 0L
        player.speed = 1f
    }

    /**
     * Log a `trace:` line now and every [TRACE_EVERY_MS] until [forMs] from now, or until a later
     * end if a running trace already reaches further. A new trigger restarts the cadence at its
     * own instant, so every window's first line is the reading at the trigger itself.
     */
    private fun traceFor(forMs: Long, phase: String) {
        val log = traceLog ?: return
        val from = hostNow()
        traceUntilMs = maxOf(traceUntilMs, from + forMs)
        traceJob?.cancel()
        traceJob = scope.launch {
            while (true) {
                traceLine(log, phase, hostNow() - from)
                if (hostNow() + TRACE_EVERY_MS > traceUntilMs) break
                delay(TRACE_EVERY_MS)
            }
        }
    }

    /**
     * `trace: pos P ms, expected E ms, err P-E ms, speed S, phase X, t T` + [PlayerControls.traceInfo].
     * `pos` is the player's raw position (nothing corrects it; the trim is on `expected`), `t` is
     * the time since the trigger. Deliberately says `err`, not `drift`: the bench parser reads
     * every "drift N ms" as a controller check.
     */
    private fun traceLine(log: (String) -> Unit, phase: String, sinceMs: Long) {
        val a = anchor ?: return
        val pos = player.positionMs
        val expected = a.expectedAt(hostNow()) + trimMs()
        log(
            "trace: pos $pos ms, expected $expected ms, err ${pos - expected} ms, speed ${player.speed}, " +
                "phase $phase, t $sinceMs${player.traceInfo}",
        )
    }

    /** Which lead this start used, and therefore which estimate its first check teaches. */
    private data class Learn(val lead: Long, val cold: Boolean)

    /** A filtered reading: the median of [n] samples of position − expected, and their max − min. */
    private data class Reading(val medianMs: Long, val spreadMs: Long, val n: Int)

    /** The player is on [a] and moving, so its position means something. */
    private fun onAnchor(a: Anchor) = player.loadedId == a.id && player.isReady && player.isPlaying

    /**
     * One filtered reading: [FILTER_SAMPLES] samples of position − expected, [FILTER_EVERY_MS]
     * apart, starting now and taking [FILTER_SPAN_MS]. Null if the player is not (or stops being)
     * on [a] meanwhile. The median ignores a level flip that covers fewer than half the samples,
     * i.e. one shorter than ~1 s.
     */
    private suspend fun read(a: Anchor): Reading? {
        val errs = LongArray(FILTER_SAMPLES)
        for (i in errs.indices) {
            if (i > 0) delay(FILTER_EVERY_MS)
            if (!onAnchor(a)) return null
            errs[i] = player.positionMs - (a.expectedAt(hostNow()) + trimMs())
        }
        errs.sort()
        return Reading(errs[errs.size / 2], errs.last() - errs.first(), errs.size)
    }

    /**
     * Drift checks for as long as this anchor plays. Each check is one filtered reading; a reading
     * of 80 ms..1 s is acted on only when it confirms the one straight before it.
     */
    private suspend fun check(learnFrom: Learn?) {
        var learn = learnFrom
        /** The previous reading, when it was outside the dead band and awaits confirmation. */
        var pending: Long? = null
        while (true) {
            val a = anchor ?: return
            if (!a.playing || held) return
            val r = read(a)
            if (r == null) {
                learn = null
                pending = null
                delay(DRIFT_CHECK_MS)
                continue
            }
            val drift = r.medianMs
            lastDriftMs = drift
            learn?.let { learnLead(it, drift) }
            learn = null
            val previous = pending
            pending = null
            when {
                abs(drift) <= RESYNC_MS -> {
                    log("drift $drift ms (start latency $startLatencyMs ms, cold $coldStartLatencyMs ms)")
                    // The next reading ends DRIFT_CHECK_MS after this one did.
                    delay(DRIFT_CHECK_MS - FILTER_SPAN_MS)
                }
                abs(drift) > SEEK_ABOVE_MS -> {
                    // One reading is enough here (see the class comment). A re-seek restarts an
                    // already-running output, which is a warm start whatever the one before it was.
                    log("drift $drift ms: seeking (lead $startLatencyMs ms)")
                    player.seekTo(a.expectedAt(hostNow()) + trimMs() + startLatencyMs)
                    traceFor(TRACE_AFTER_MS, "seek")
                    learn = Learn(startLatencyMs, cold = false)
                    delay(EARLY_CHECK_MS)
                }
                previous == null || !confirms(previous, drift) -> {
                    // No delay: the confirming reading is the next FILTER_SPAN_MS of samples.
                    log(
                        "check: median err $drift ms (spread ${r.spreadMs} ms, n ${r.n}), waiting for confirmation" +
                            (previous?.let { " (previous $it ms not confirmed)" } ?: ""),
                    )
                    pending = drift
                }
                else -> {
                    nudge(a, drift)
                    delay(EARLY_CHECK_MS - NUDGE_DONE_AFTER_MS)
                }
            }
        }
    }

    /** [later] confirms [earlier]: same side of the timeline and within [CONFIRM_WITHIN_MS]. */
    private fun confirms(earlier: Long, later: Long) =
        (earlier > 0) == (later > 0) && abs(later - earlier) <= CONFIRM_WITHIN_MS

    /** Teach the lead [from] used by the first filtered reading after its start. */
    private fun learnLead(from: Learn, drift: Long) {
        if (abs(drift) >= SEEK_ABOVE_MS) return
        // `lead - drift` is a direct estimate of the output delay this start actually had.
        // Damped, not averaged: a straight mean of two (weight 1/2) overshoots, because the
        // cold-A2DP start and the warm resume have genuinely different lags. Measured on the
        // Pixel 8 with AirPods, weight 1/2 rang -693 -> +428 -> +173 -> +93 ms, and each of
        // those corrections is a multi-second rate nudge.
        val measured = (from.lead - drift).coerceIn(0, MAX_START_LATENCY_MS)
        if (from.cold) {
            coldStartLatencyMs += (measured - coldStartLatencyMs) / LEARN_DIVISOR
        } else {
            startLatencyMs += (measured - startLatencyMs) / LEARN_DIVISOR
        }
    }

    /** Play at up to ±[MAX_NUDGE] speed for as long as it takes to absorb [drift]. */
    private suspend fun nudge(a: Anchor, drift: Long) {
        val speed = (1f - drift.toFloat() / NUDGE_WINDOW_MS).coerceIn(1f - MAX_NUDGE, 1f + MAX_NUDGE)
        val durationMs = (abs(drift) / abs(speed - 1f)).toLong()
        log("drift $drift ms: speed $speed for $durationMs ms")
        player.speed = speed
        traceFor(durationMs + TRACE_AFTER_MS, "nudge")
        try {
            delay(durationMs)
        } finally {
            player.speed = 1f
        }
        // One raw reading a second after the reset (the speed change shows in the position ~1 s
        // late), for the bench's "where did the nudge land". Logging only; the decision is the
        // filtered check that starts EARLY_CHECK_MS after the reset.
        delay(NUDGE_DONE_AFTER_MS)
        log("nudge done: drift ${player.positionMs - (a.expectedAt(hostNow()) + trimMs())} ms")
    }

    companion object {
        const val DRIFT_CHECK_MS = 10_000L
        /** From a start, a re-seek or the end of a nudge to the first sample of the next reading. */
        const val EARLY_CHECK_MS = 2_000L
        /** PROTOCOL.md: correct when off by more than 80 ms. */
        const val RESYNC_MS = 80L
        /** Samples per filtered reading (odd, so the median is one of them). */
        const val FILTER_SAMPLES = 9
        const val FILTER_EVERY_MS = 250L
        /** How long one filtered reading takes: first sample to last. */
        const val FILTER_SPAN_MS = (FILTER_SAMPLES - 1) * FILTER_EVERY_MS
        /** Two consecutive readings confirm each other when they are this close (and same sign). */
        const val CONFIRM_WITHIN_MS = 100L
        /** `nudge done:` is read this long after the speed reset. */
        const val NUDGE_DONE_AFTER_MS = 1_000L
        private const val SEEK_ABOVE_MS = 1_000L
        private const val PREPARE_MS = 250L
        /** Drift is spread over this long: 80 ms asks for 0.992 for 10 s. */
        private const val NUDGE_WINDOW_MS = 10_000f
        /** PROTOCOL.md: at most ±2 %. A 1 s drift then takes ~50 s, which two riders never notice. */
        const val MAX_NUDGE = 0.02f
        const val DEFAULT_START_LATENCY_MS = 300L
        /** How hard one measurement pulls the learned start latency (1/4 of the way). */
        const val LEARN_DIVISOR = 4
        private const val MAX_START_LATENCY_MS = 1_000L
        private const val TAG = "SyncController"
        /** The trace keeps going this long after a start, a re-seek or the end of a nudge. */
        const val TRACE_AFTER_MS = 5_000L
        const val TRACE_EVERY_MS = 1_000L
    }
}
