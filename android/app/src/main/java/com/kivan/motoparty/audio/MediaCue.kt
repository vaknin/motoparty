package com.kivan.motoparty.audio

/**
 * When a sound that plays on the **media route** may be played (F9b, 2026-09-20). Pure: no Android,
 * no clock of its own — every event carries the time it happened, and the caller does the playing.
 * The sibling of [LiveCue], which does the same job for the one sound that plays on the *call*
 * route.
 *
 * **The bug this closes.** The CLOSED earcon used to be played the instant `exitCall()` returned
 * ([TalkAudio]); the ERROR earcon and the spoken replies were issued the same way, right behind a
 * route teardown. `exitCall` normally blocks 0.7–1.0 s, by which time the media route is back — but
 * it was measured at **6 ms** once, and that beep went out into the SCO teardown: it was heard on
 * the phone speaker *and* the AirPods, cut off. A sound timed by a call that *happens* to block is
 * gated on nothing at all.
 *
 * **The rule.** A media-route sound may play when
 *
 *  a. **the call route has been released** — `exitCall` has returned with nesting depth 0
 *     ([routeReleased]); nothing this app holds is in call mode any more; **and**
 *  b. **only if the route being released was SCO**, the audio framework has reported a
 *     communication device **other than** `bt_sco` *after* that release ([device]). Tearing an SCO
 *     link down is the part that takes real time and the framework's own `earpiece`/`none` dispatch
 *     is the honest end of it — the same signal F8 uses for the other edge. Off SCO there is no link
 *     to tear down, so (b) does not apply and the sound plays as soon as (a) holds: on the
 *     2026-09-20 earpiece bench `exitCall` took 5–16 ms and nothing must be added to that.
 *
 * A sound asked for while **no** route is held and none is being torn down plays **at once** — the
 * common case (an error earcon with no client connected, a spoken reply with no recogniser route),
 * and it must stay instant.
 *
 * [tick] is the safety net: ~[TIMEOUT_MS] after the request the sound is played anyway and logged
 * `fallback`, so a missing signal can cost a late sound but never a silent one.
 *
 * **A re-open drops a pending CLOSED** ([routeHeld], and [dropClosed] for a talk that takes no
 * call route): the talk is back, so that beep is stale and would be a lie. Any other pending sound
 * is kept and waits for the *next* release — an error or a spoken reply is still true whatever the
 * route does.
 *
 * **The teardown can report before the release** (2026-09-29 device run): `exitCall`'s `setMode`
 * blocks ~660 ms, and the framework's `earpiece` report lands ~80 ms *before* it returns. Waiting
 * for a report *after* the release then waited for one that had already come: every CLOSED earcon
 * of an earbud talk fell back to the 2 s timer, and the drain was never ended, so every later
 * media sound (all host-mic talks' CLOSED beeps included) waited 2 s too — one played into the
 * next talk. So a non-`bt_sco` report seen since [routeHeld] counts at the release, and a fallback
 * ends the drain.
 */
class MediaCue(private val timeoutMs: Long = TIMEOUT_MS) {

    /** Which sound; only the word in the log line depends on it, except [CLOSED] — see [routeHeld]. */
    enum class Kind(val word: String) {
        CLOSED("closed"),
        ERROR("error"),
        OK("ok"),
        ANNOUNCE("announce"),
    }

    /**
     * Play this sound now. Every offset is measured from the moment the sound was *asked for*, so
     * `played` reads as "how long it waited". [line] is the exact text the bench reads (pinned by
     * `MediaCueTest`).
     */
    data class Play(
        /** The caller's own handle: [MediaCue] never holds the sound, only the decision. */
        val id: Int,
        val kind: Kind,
        /** The call route was released, ms after the request; 0 = already released, null = never. */
        val releasedMs: Long?,
        /** Did this sound have to wait for a non-`bt_sco` device, i.e. did condition (b) apply? */
        val neededDevice: Boolean,
        /** That device's type in the framework's spelling ([ScoRule.describe]), or null. */
        val deviceType: String?,
        /** That device reported, ms after the request; null = it never did. */
        val deviceMs: Long?,
        val playedMs: Long,
        /** The timer fired it: one of the conditions never arrived. */
        val fallback: Boolean,
    ) {
        fun line(): String =
            "media cue: ${kind.word}, released ${offset(releasedMs)}, device ${device()}, " +
                "played +$playedMs ms (${if (fallback) "fallback" else "both"})"

        /** `n/a` = nothing to wait for (no SCO route was released); `none` = it never reported. */
        private fun device(): String = when {
            !neededDevice -> "n/a"
            deviceMs == null -> "none"
            else -> "${deviceType ?: "none"} +$deviceMs ms"
        }

        private fun offset(v: Long?): String = if (v == null) "none" else "+$v ms"
    }

    private class Req(val id: Int, val kind: Kind, val atMs: Long)

    /** A call route is held, or is being torn down: `enterCall` ran and `exitCall` has not returned. */
    private var held = false
    /** The released route was SCO and the framework has not reported another device yet. */
    private var draining = false
    private var releasedAt: Long? = null
    private var deviceAt: Long? = null
    private var deviceType: String? = null
    /** The framework's last raw report since [routeHeld] (any report, draining or not), or null. */
    private var lastReport: Int? = null
    private var lastReportAt: Long? = null
    private val pending = ArrayList<Req>()

    /**
     * A sound of [kind] is wanted. Returns the [Play] when it may be played right now — no route
     * held, nothing draining — and null when it has to wait, in which case the caller keeps [id]
     * and plays it when this class hands it back.
     */
    fun request(id: Int, kind: Kind, atMs: Long): Play? {
        if (held || draining) {
            pending += Req(id, kind, atMs)
            return null
        }
        // Nothing to wait for: do not report a release or a device this sound never waited on.
        return Play(
            id = id, kind = kind, releasedMs = 0, neededDevice = false,
            deviceType = null, deviceMs = null, playedMs = 0, fallback = false,
        )
    }

    /**
     * This app took a call route (`enterCall`, nesting depth 0 → 1). Returns the ids of the sounds
     * dropped for good: a pending CLOSED earcon, whose talk is open again.
     */
    fun routeHeld(atMs: Long): List<Int> {
        held = true
        draining = false
        releasedAt = null
        deviceAt = null
        deviceType = null
        lastReport = null
        lastReportAt = null
        return dropClosed()
    }

    /**
     * A talk opened again without taking a call route (a host-mic talk): a pending CLOSED earcon
     * is stale. Returns the ids dropped; everything else stays pending.
     */
    fun dropClosed(): List<Int> {
        val stale = pending.filter { it.kind == Kind.CLOSED }.map { it.id }
        pending.removeAll { it.kind == Kind.CLOSED }
        return stale
    }

    /**
     * `exitCall` has returned with nesting depth 0: condition (a). [wasSco] is whether the route it
     * just gave up was a Bluetooth SCO one — only then does condition (b) apply, and only the caller
     * knows, because `exitCall` has already cleared its own record of it by the time it returns.
     */
    fun routeReleased(wasSco: Boolean, atMs: Long): List<Play> {
        held = false
        releasedAt = atMs
        draining = wasSco
        deviceAt = null
        deviceType = null
        val last = lastReportAt
        if (wasSco && last != null && !ScoRule.connected(lastReport)) {
            // The teardown already reported while exitCall was still blocking: it is over.
            draining = false
            deviceAt = last
            deviceType = ScoRule.describe(lastReport)
        }
        return if (draining) emptyList() else flush(atMs)
    }

    /**
     * The audio framework reported its communication device ([ScoWatch]'s raw report, every one of
     * them — the de-duplicated path cannot be used here, because the release above has already
     * flipped that cached flag). Anything other than `bt_sco`, including none, ends the drain.
     */
    fun device(type: Int?, atMs: Long): List<Play> {
        if (held) {
            lastReport = type
            lastReportAt = atMs
        }
        if (!draining) return emptyList()
        if (ScoRule.connected(type)) return emptyList() // still the link we are waiting to lose
        draining = false
        deviceAt = atMs
        deviceType = ScoRule.describe(type)
        return flush(atMs)
    }

    /** The safety net: anything asked for more than [timeoutMs] ago is played now. */
    fun tick(atMs: Long): List<Play> {
        val due = pending.filter { atMs - it.atMs >= timeoutMs }
        if (due.isEmpty()) return emptyList()
        pending.removeAll(due.toSet())
        val plays = due.map { play(it, atMs, fallback = true) }
        // The report never came: stop waiting for it, or every later sound waits 2 s as well.
        if (!draining) return plays
        draining = false
        return plays + flush(atMs)
    }

    private fun flush(atMs: Long): List<Play> {
        val out = pending.map { play(it, atMs, fallback = false) }
        pending.clear()
        return out
    }

    private fun play(r: Req, atMs: Long, fallback: Boolean) = Play(
        id = r.id,
        kind = r.kind,
        // A release that happened before the request is "+0 ms": it waited for nothing.
        releasedMs = releasedAt?.let { maxOf(0L, it - r.atMs) },
        neededDevice = draining || deviceAt != null,
        deviceType = deviceType,
        deviceMs = deviceAt?.let { maxOf(0L, it - r.atMs) },
        playedMs = maxOf(0L, atMs - r.atMs),
        fallback = fallback,
    )

    companion object {
        /** The sound can be late, never missing: after this it is played whatever the signals say. */
        const val TIMEOUT_MS = 2_000L
    }
}
