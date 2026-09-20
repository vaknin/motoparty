package com.kivan.motoparty.audio

/**
 * When the "live" earcon may be played (F7, 2026-09-20). Pure: no Android, no clock of its own —
 * every event carries the time it happened, and the caller does the beeping.
 *
 * The beep means **"your mic is live, talk now"**, so it must not be a timer. Before F7 it fired
 * 400 ms after `enterCall` + `VoiceEngine.start` returned; on the 2026-09-20 AirPods bench
 * (`tools/bench/results/2026-09-20-d1-talk-5`) that was up to 630 ms *before* the Bluetooth SCO
 * link existed. Two things have to be true instead:
 *
 *  a. **the microphone delivers** — [captureUp], the capture loop's first frame of this talk;
 *  b. **the route is really up** — for a Bluetooth SCO device the SCO audio link is connected
 *     ([scoConnected], or already connected when the route came up, which is what a fast re-open
 *     that kept the route looks like). Any other route (earpiece, speaker, wired, BLE headset)
 *     is up as soon as [route] says so.
 *
 * Neither alone is enough: in the bench's second cycle the first captured frame arrived 0.76 s
 * *before* SCO was up (the mic was delivering something, not the rider's voice), and the SCO link
 * comes up 1.17–1.38 s after the press while the first frame follows 62–166 ms later.
 *
 * [tick] is the safety net: ~[TIMEOUT_MS] after the open the beep is played anyway and logged as
 * `fallback`, so a missing signal can cost a late beep but never a silent one.
 *
 * Exactly one [Fire] per open: a session that fired ignores everything until the next [open], an
 * SCO link that flaps mid-talk does not beep twice, and a talk that closes before it was ready
 * never beeps at all.
 */
class LiveCue(private val timeoutMs: Long = TIMEOUT_MS) {

    /**
     * Play the earcon now. The offsets are measured from the talk open and are what the bench
     * reads; [line] is the exact text (pinned by `LiveCueTest`).
     */
    data class Fire(
        val session: Int,
        /** First captured frame, ms after the open; null = never read one (a [tick] fire). */
        val captureUpMs: Long?,
        /** SCO connected, ms after the open; null = not seen. Meaningless unless [needsSco]. */
        val scoMs: Long?,
        /** Whether this talk's route is a Bluetooth SCO one; null = the route never reported. */
        val needsSco: Boolean?,
        val firedMs: Long,
        /** The timer fired it: one of the two conditions never arrived. */
        val fallback: Boolean,
    ) {
        fun line(): String =
            "live cue: session $session, capture up ${offset(captureUpMs)}, sco ${sco()}, " +
                "fired +$firedMs ms (${if (fallback) "fallback" else "both"})"

        private fun sco(): String = when (needsSco) {
            null -> "unknown"
            false -> "n/a"
            true -> offset(scoMs)
        }

        private fun offset(v: Long?): String = if (v == null) "none" else "+$v ms"
    }

    private var session: Int? = null
    private var openedAt = 0L
    private var captureAt: Long? = null
    private var scoAt: Long? = null
    private var needsSco: Boolean? = null
    private var fired = false

    /** Talk [session] opened at [atMs]. Whatever the previous talk knew is void. */
    fun open(session: Int, atMs: Long) {
        this.session = session
        openedAt = atMs
        captureAt = null
        scoAt = null
        needsSco = null
        fired = false
    }

    /** Talk closed (or was never answered): no beep for it, ever. */
    fun close() {
        session = null
        fired = false
    }

    /**
     * The call route of [session] is up: `enterCall` returned. [needsSco] is true for a Bluetooth
     * SCO device, whose link is established asynchronously *after* this; [scoConnected] is the
     * link state right now, which is already true when a fast re-open kept the route.
     */
    fun route(session: Int, needsSco: Boolean, scoConnected: Boolean, atMs: Long): Fire? {
        if (session != this.session) return null
        this.needsSco = needsSco
        // No connect event of our own and the link is up: it was already up when this talk opened
        // (a fast re-open that kept the route), so that is when it counts from.
        if (needsSco && scoConnected && scoAt == null) scoAt = openedAt
        return ready(atMs)
    }

    /** The capture loop of [session] read its first frame. */
    fun captureUp(session: Int, atMs: Long): Fire? {
        if (session != this.session) return null
        if (captureAt == null) captureAt = maxOf(atMs, openedAt)
        return ready(atMs)
    }

    /**
     * The SCO audio link came up. A system-wide signal, so it carries no session and applies to
     * whichever talk is open.
     */
    fun scoConnected(atMs: Long): Fire? {
        if (session == null) return null
        if (scoAt == null) scoAt = maxOf(atMs, openedAt)
        return ready(atMs)
    }

    /** The SCO link dropped. Before the beep that undoes condition (b); after it, nothing. */
    fun scoDisconnected() {
        if (fired) return
        scoAt = null
    }

    /** The fallback timer of [session]. Fires once [timeoutMs] have passed since the open. */
    fun tick(session: Int, atMs: Long): Fire? {
        if (session != this.session || fired) return null
        if (atMs - openedAt < timeoutMs) return null
        return fire(atMs, fallback = true)
    }

    private fun ready(atMs: Long): Fire? {
        if (fired) return null
        val routeUp = when (needsSco) {
            null -> false // the route has not reported yet
            false -> true
            true -> scoAt != null
        }
        if (!routeUp || captureAt == null) return null
        return fire(atMs, fallback = false)
    }

    private fun fire(atMs: Long, fallback: Boolean): Fire {
        fired = true
        return Fire(
            session = session!!,
            captureUpMs = captureAt?.minus(openedAt),
            scoMs = scoAt?.minus(openedAt),
            needsSco = needsSco,
            firedMs = maxOf(0L, atMs - openedAt),
            fallback = fallback,
        )
    }

    companion object {
        /** The beep can be late, never missing: after this it is played whatever the signals say. */
        const val TIMEOUT_MS = 2_500L
    }
}
