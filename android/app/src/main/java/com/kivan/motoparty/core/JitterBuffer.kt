package com.kivan.motoparty.core

import java.util.TreeMap

/**
 * Adaptive jitter buffer, PROTOCOL.md "Voice". The playback loop calls [pull] once per 20 ms
 * frame and decodes whatever it returns.
 *
 * - Talk spurts: the first frame of a spurt plays [targetMs] after it arrived. A spurt starts
 *   at the first packet, and after every silence gap (a `ts` jump with contiguous `seq`).
 * - Underrun = a packet arrives after its play-out slot: target +20 ms (max 200, at most once
 *   per spurt). 10 s without
 *   one: target -20 ms (min 40). Either change takes effect at the next spurt.
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

    private val packets = TreeMap<Long, Entry>()
    private val keepaliveSeqs = java.util.TreeSet<Long>()
    /** True while inside a spurt: frames are due at [nextTs]. False: waiting for a spurt start. */
    private var playing = false
    private var nextTs = 0L
    private var lastSeq: Long? = null
    private var lastUnderrunMs: Long? = null
    private var tsRef: Long? = null
    private var seqRef: Long? = null
    private var emptyPulls = 0
    private var raisedThisSpurt = false

    fun reset() {
        packets.clear()
        keepaliveSeqs.clear()
        playing = false
        lastSeq = null
        tsRef = null
        seqRef = null
        targetMs = MIN_MS
        lastUnderrunMs = null
    }

    fun insert(seq: Int, ts: Long, payload: ByteArray) {
        val now = nowMs()
        if (lastUnderrunMs == null) lastUnderrunMs = now
        val u = unwrap(ts, tsRef, 1L shl 32).also { tsRef = it }
        val s = unwrap(seq.toLong(), seqRef, 1L shl 16).also { seqRef = it }
        if (playing && (u - nextTs > RESYNC || nextTs - u > RESYNC)) {
            // The sender restarted or we fell hopelessly behind: start over, keep the target.
            packets.clear()
            playing = false
            lastSeq = null
        }
        if (playing && u < nextTs - FRAME / 2) {
            underruns++
            lastUnderrunMs = now
            // At most one raise per talk spurt: one late burst is one underrun event.
            if (!raisedThisSpurt) targetMs = minOf(MAX_MS, targetMs + STEP_MS)
            raisedThisSpurt = true
            return
        }
        packets.putIfAbsent(u, Entry(s, now, payload))
        while (packets.size > MAX_PACKETS) packets.pollFirstEntry()
    }

    fun insertKeepalive(seq: Int) {
        val s = unwrap(seq.toLong(), seqRef, 1L shl 16).also { seqRef = it }
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
            val dropped = packets.pollFirstEntry()
            lastSeq = dropped.value.seq
            nextTs = dropped.key + FRAME
        }

        val first = packets.firstEntry()
        if (!playing) {
            if (first == null || now - first.value.arrivedMs < targetMs) return Out.Silence
            playing = true
            raisedThisSpurt = false
            nextTs = first.key
        }
        if (first != null && first.key < nextTs + FRAME / 2) {
            packets.pollFirstEntry()
            lastSeq = first.value.seq
            nextTs = first.key + FRAME
            emptyPulls = 0
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
            return if (++emptyPulls <= CONCEAL_EMPTY_FRAMES) Out.Conceal else Out.Silence
        }
        emptyPulls = 0
        return if (first.key < nextTs + FRAME / 2) Out.Fec(first.value.payload) else Out.Conceal
    }

    /** True if every seq strictly between the last played packet and [seq] was a keepalive. */
    private fun onlyKeepalivesBefore(seq: Long): Boolean {
        val last = lastSeq ?: return true
        if (seq <= last || seq - last - 1 > MAX_KEEPALIVES) return false
        var s = last + 1
        while (s < seq) {
            if (s !in keepaliveSeqs) return false
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
        private const val RESYNC = 3L * 16_000
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
