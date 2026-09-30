package com.kivan.motoparty.core

import java.util.TreeMap

/**
 * Adaptive jitter buffer, PROTOCOL.md "Voice". The playback loop calls [pull] once per 20 ms
 * frame and decodes whatever it returns.
 *
 * - Talk spurts: the first frame of a spurt plays [targetMs] after it arrived. A spurt starts
 *   at the first packet, and after every silence gap (a `ts` jump with contiguous `seq`), also
 *   when the packet after the gap comes later than the old spurt's play-out clock.
 * - Shedding: a backlog (play-out stalled, or the output clock is slower than the sender's)
 *   would otherwise stay as extra delay until the talk ends, since an earbud mic never goes
 *   silent. Oldest frames are dropped at spurt start until the queue spans the target, and
 *   during a spurt when the smallest depth over [SHED_WINDOW] frames stays above the target.
 *   Shed frames are neither loss nor underruns ([shed] counts them).
 * - Underrun = a packet arrives after its play-out slot: target +20 ms (max 200, at most once
 *   per spurt). 10 s without
 *   one: target -20 ms (min 40). Either change takes effect at the next spurt.
 * - Re-anchor (what is left of it once a late run with contiguous `seq` starts a new spurt at
 *   its second packet): packets that keep arriving late for [REANCHOR_AFTER_MS] with nothing played in
 *   between mean our play-out clock is ahead of the stream (the output took a burst of frames at
 *   a route switch, or the sender lost frames without its `ts` jumping). Waiting cannot fix that
 *   short of [RESYNC]; the next late packet starts a new spurt instead of being dropped.
 * - Keepalives share the `seq` counter ([insertKeepalive]); they are never loss or activity.
 *   A real `seq` gap is loss: FEC from the successor if it is the very next frame, else PLC.
 *
 * `seq`/`ts` wrap-around is handled by unwrapping into Longs. Not thread-safe; callers lock.
 */
class JitterBuffer(private val nowMs: () -> Long) {
    sealed interface Out {
        /** Decode this packet normally. */
        class Play(val payload: ByteArray) : Out
        /** The current frame was lost; decode the FEC copy carried by this successor packet. */
        class Fec(val successor: ByteArray) : Out
        /** Lost or not (yet) arrived mid-spurt: packet-loss concealment. */
        data object Conceal : Out
        /** Between spurts, or buffering the next one: output silence, nothing to decode. */
        data object Silence : Out
    }

    private class Entry(val seq: Long, val arrivedMs: Long, val payload: ByteArray)

    var targetMs = MIN_MS
        private set
    var underruns = 0
        private set

    // Per-talk counters for the summary logged at talk close ([reset] zeroes them; [underruns]
    // deliberately keeps counting, the UI shows it live).
    /** Audio packets handed to [insert], late ones included. */
    var received = 0
        private set
    /** Keepalives handed to [insertKeepalive]: they fill `seq` without being audio. */
    var keepalives = 0
        private set
    var fecUsed = 0
        private set
    var concealed = 0
        private set
    /** Late runs that started a new spurt instead of being dropped (see [REANCHOR_AFTER_MS]). */
    var reanchors = 0
        private set
    /** Queued frames dropped to get rid of a backlog (spurt start, 1 s window, hard cap). */
    var shed = 0
        private set
    /** Largest queue depth seen when a frame was due, ms. */
    var maxDepthMs = 0
        private set
    /** Mean queue depth over the frames due since [reset], ms; 0 before the first. */
    val meanDepthMs: Int get() = if (depthPulls == 0L) 0 else (depthSumMs / depthPulls).toInt()
    private var depthSumMs = 0L
    private var depthPulls = 0L
    private var minSeqSeen: Long? = null
    private var maxSeqSeen = 0L

    /**
     * Sequence numbers covered since [reset] (lowest to highest seen, unwrapped); minus
     * [received] and [keepalives] that is loss, see [TalkStats.lost].
     */
    val seqSpan: Long get() = minSeqSeen?.let { maxSeqSeen - it + 1 } ?: 0L

    private val packets = TreeMap<Long, Entry>()
    private val keepaliveSeqs = java.util.TreeSet<Long>()
    /** Audio seqs that arrived after their slot and were dropped: seen, so not a gap. */
    private val lateSeqs = java.util.TreeSet<Long>()
    /** True while inside a spurt: frames are due at [nextTs]. False: waiting for a spurt start. */
    private var playing = false
    private var nextTs = 0L
    private var lastSeq: Long? = null
    private var lastUnderrunMs: Long? = null
    private var tsRef: Long? = null
    private var seqRef: Long? = null
    private var emptyPulls = 0
    private var raisedThisSpurt = false
    /** Arrival of the first late packet since the last frame played; null = none. */
    private var lateSinceMs: Long? = null
    /** `ts` of the last frame returned as [Out.Play]; null = none since a restart. */
    private var lastPlayedTs: Long? = null
    /** Frames due in the current shed window, and the smallest queue size among them. */
    private var windowPulls = 0
    private var windowMin = Int.MAX_VALUE

    fun reset() {
        packets.clear()
        keepaliveSeqs.clear()
        lateSeqs.clear()
        playing = false
        nextTs = 0L
        emptyPulls = 0
        raisedThisSpurt = false
        lateSinceMs = null
        lastPlayedTs = null
        windowPulls = 0
        windowMin = Int.MAX_VALUE
        lastSeq = null
        tsRef = null
        seqRef = null
        targetMs = MIN_MS
        lastUnderrunMs = null
        received = 0
        keepalives = 0
        fecUsed = 0
        concealed = 0
        reanchors = 0
        shed = 0
        maxDepthMs = 0
        depthSumMs = 0L
        depthPulls = 0L
        minSeqSeen = null
        maxSeqSeen = 0L
    }

    fun insert(seq: Int, ts: Long, payload: ByteArray) {
        val now = nowMs()
        if (lastUnderrunMs == null) lastUnderrunMs = now
        val u = unwrap(ts, tsRef, 1L shl 32).also { tsRef = it }
        val s = unwrap(seq.toLong(), seqRef, 1L shl 16).also { seqRef = it }
        received++
        noteSeq(s)
        if (playing && (u - nextTs > RESYNC || nextTs - u > RESYNC)) {
            // The sender restarted or we fell hopelessly behind: start over, keep the target.
            packets.clear()
            playing = false
            lastSeq = null
            lastPlayedTs = null
        }
        if (playing && u < nextTs - FRAME / 2) {
            val played = lastPlayedTs
            if (packets.isEmpty() && played != null && u - played >= 2 * FRAME && onlyKeepalivesBefore(s)) {
                // Not late: the sender was silent since the last played frame (`ts` jumped, `seq`
                // did not) and this first packet of the next spurt merely took longer than the
                // old spurt's timeline allowed. End that spurt; [pull] starts the new one.
                playing = false
                packets[u] = Entry(s, now, payload)
                return
            }
            val since = lateSinceMs ?: now.also { lateSinceMs = it }
            if (now - since >= REANCHOR_AFTER_MS) {
                // Late for a while and nothing played: the play-out clock is ahead of this
                // stream and stays there (both advance at 50 frames/s). Every later packet would
                // be dropped until the talk ends. Start a new spurt at this packet, keep the target
                // (already raised by the first late packet of this run).
                reanchors++
                playing = false
                lastSeq = null
                lateSinceMs = null
                packets.headMap(u).clear()
                packets.putIfAbsent(u, Entry(s, now, payload))
                return
            }
            underruns++
            lastUnderrunMs = now
            // At most one raise per talk spurt: one late burst is one underrun event.
            if (!raisedThisSpurt) targetMs = minOf(MAX_MS, targetMs + STEP_MS)
            raisedThisSpurt = true
            // Not loss either: if it was the spurt's last packet, the silence gap after it must
            // still start a new spurt (at the raised target) instead of being concealed.
            lateSeqs.add(s)
            while (lateSeqs.size > MAX_KEEPALIVES) lateSeqs.pollFirst()
            return
        }
        packets.putIfAbsent(u, Entry(s, now, payload))
        while (packets.size > MAX_PACKETS) packets.pollFirstEntry()
    }

    fun insertKeepalive(seq: Int) {
        val s = unwrap(seq.toLong(), seqRef, 1L shl 16).also { seqRef = it }
        keepalives++
        noteSeq(s)
        keepaliveSeqs.add(s)
        while (keepaliveSeqs.size > MAX_KEEPALIVES) keepaliveSeqs.pollFirst()
    }

    fun pull(): Out {
        val now = nowMs()
        lastUnderrunMs?.let {
            if (now - it >= LOWER_AFTER_MS) {
                lastUnderrunMs = now
                if (targetMs > MIN_MS) targetMs -= STEP_MS
            }
        }
        // Hard cap only (e.g. clock drift over a very long spurt); normal changes wait for a spurt.
        while (packets.size * VoicePacket.FRAME_MS > MAX_MS + BACKLOG_SLACK_MS) {
            shedOldest()
        }

        if (!playing) {
            val oldest = packets.firstEntry()
            if (oldest == null || now - oldest.value.arrivedMs < targetMs) return Out.Silence
            // A burst queued before the spurt could start: keep no more than the target of it.
            while (packets.lastKey() - packets.firstKey() > targetMs * SAMPLES_PER_MS) shedOldest()
            playing = true
            raisedThisSpurt = false
            nextTs = packets.firstKey()
            windowPulls = 0
            windowMin = Int.MAX_VALUE
        }
        // A frame is due: note the depth, and once per window shed what never drained.
        val depth = packets.size
        val depthMs = depth * VoicePacket.FRAME_MS
        depthSumMs += depthMs
        depthPulls++
        if (depthMs > maxDepthMs) maxDepthMs = depthMs
        if (depth < windowMin) windowMin = depth
        if (++windowPulls >= SHED_WINDOW) {
            val minMs = windowMin * VoicePacket.FRAME_MS
            val drop = when {
                minMs > targetMs + SHED_ALL_ABOVE_MS -> (minMs - targetMs) / VoicePacket.FRAME_MS
                minMs > targetMs + SHED_ONE_ABOVE_MS -> 1
                else -> 0
            }
            repeat(minOf(drop, packets.size)) { shedOldest() }
            windowPulls = 0
            windowMin = Int.MAX_VALUE
        }

        val first = packets.firstEntry()
        if (first != null && first.key < nextTs + FRAME / 2) {
            packets.pollFirstEntry()
            lastSeq = first.value.seq
            lastPlayedTs = first.key
            nextTs = first.key + FRAME
            emptyPulls = 0
            lateSinceMs = null
            return Out.Play(first.value.payload)
        }
        if (first != null && onlyKeepalivesBefore(first.value.seq)) {
            // Silence gap: the next packet starts a new spurt with the current target.
            playing = false
            return pull()
        }
        nextTs += FRAME
        if (first == null) {
            // Nothing queued: loss or the sender went quiet. Conceal briefly, then fall silent
            // rather than let PLC stretch the last phoneme.
            return if (++emptyPulls <= CONCEAL_EMPTY_FRAMES) Out.Conceal.also { concealed++ } else Out.Silence
        }
        emptyPulls = 0
        return if (first.key < nextTs + FRAME / 2) {
            fecUsed++
            lateSinceMs = null
            Out.Fec(first.value.payload)
        } else {
            concealed++
            Out.Conceal
        }
    }

    /** Drops the oldest queued frame as if it had played: its successor is next, not a gap. */
    private fun shedOldest() {
        val dropped = packets.pollFirstEntry() ?: return
        lastSeq = dropped.value.seq
        nextTs = dropped.key + FRAME
        shed++
    }

    /**
     * Remembers the span of `seq` values seen, so loss can be counted at the end of a talk. The
     * lowest, not the first: a talk whose first two packets arrive swapped would otherwise leave
     * the earlier one outside the span and hide one real loss.
     */
    private fun noteSeq(s: Long) {
        val min = minSeqSeen
        if (min == null) {
            minSeqSeen = s
            maxSeqSeen = s
        } else {
            if (s < min) minSeqSeen = s
            if (s > maxSeqSeen) maxSeqSeen = s
        }
    }

    /**
     * True if every seq strictly between the last played packet and [seq] was a keepalive (or
     * an audio packet that came too late to play: it arrived, it just could not be used).
     */
    private fun onlyKeepalivesBefore(seq: Long): Boolean {
        val last = lastSeq ?: return true
        if (seq <= last || seq - last - 1 > MAX_KEEPALIVES) return false
        var s = last + 1
        while (s < seq) {
            if (s !in keepaliveSeqs && s !in lateSeqs) return false
            s++
        }
        return true
    }

    companion object {
        const val FRAME = VoicePacket.FRAME_SAMPLES.toLong()
        const val MIN_MS = 40
        const val MAX_MS = 200
        const val STEP_MS = 20
        const val LOWER_AFTER_MS = 10_000L
        private const val BACKLOG_SLACK_MS = 200
        private const val SAMPLES_PER_MS = 16
        /** Frames due per shed window (1 s). */
        const val SHED_WINDOW = 50
        /** Smallest depth of a window this far above the target: shed one frame. */
        private const val SHED_ONE_ABOVE_MS = 40
        /** ...and this far above: a stall, shed the whole excess at once. */
        private const val SHED_ALL_ABOVE_MS = 120
        private const val RESYNC = 3L * 16_000
        /** How long packets may keep arriving late, nothing played, before [insert] re-anchors. */
        const val REANCHOR_AFTER_MS = 100L
        private const val MAX_PACKETS = 100
        private const val MAX_KEEPALIVES = 16
        private const val CONCEAL_EMPTY_FRAMES = 3

        /** Unwraps a modular counter to the value nearest the previous one. */
        fun unwrap(raw: Long, ref: Long?, modulus: Long): Long {
            if (ref == null) return raw
            val base = ref - Math.floorMod(ref, modulus)
            var best = base + raw
            for (candidate in longArrayOf(best - modulus, best + modulus)) {
                if (kotlin.math.abs(candidate - ref) < kotlin.math.abs(best - ref)) best = candidate
            }
            return best
        }
    }
}
