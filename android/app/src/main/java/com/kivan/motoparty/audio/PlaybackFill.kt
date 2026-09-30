package com.kivan.motoparty.audio

/**
 * A size that starts small and grows by [step] each time the track's underrun count has risen,
 * never past [max] (audit L3). Small is a guess about the device's mixer; an underrun says the
 * guess was too low, and [max] is what the track did before, so the worst case is a few glitches
 * on the way back to the old behaviour. It never shrinks. Pure: the callers own the `AudioTrack`.
 */
class GrowOnUnderrun(start: Int, private val step: Int, private val max: Int) {
    var size = start.coerceAtMost(max)
        private set
    /** How many times it grew. */
    var grown = 0
        private set
    private var seen = 0

    /** Underruns from before the first frames were in the buffer say nothing about its size. */
    fun baseline(count: Int) {
        seen = count
    }

    /** [count] = `AudioTrack.underrunCount` now. Returns the new size when it just grew. */
    fun underruns(count: Int): Int? {
        if (count <= seen) return null
        seen = count
        if (size >= max) return null
        size = minOf(max, size + step)
        grown++
        return size
    }
}

/**
 * What to do with each 20 ms chunk for the passenger's track in a host-mic talk (audit L3, L4).
 * The capture thread writes non-blocking, so the track's fill is wherever the two clocks leave
 * it: with an output slower than the USB input that was "full", i.e. the whole buffer as delay,
 * and a partial write into the last free samples broke the waveform.
 *
 * - [Verdict.FULL]: less than one chunk free. Drop the whole chunk, never a part of it.
 * - [Verdict.TRIM]: the smallest fill over the last [WINDOW] chunks stayed a chunk or more above
 *   [holdSamples]: that much is delay nobody needs, drop one chunk. At most one per window.
 * - Unknown fill (the position read made no sense): write as before and let the track decide.
 *
 * [hold] starts at [holdSamples] and grows on underruns up to the capacity, where TRIM can no
 * longer happen and only the whole-chunk rule is left. Sizes in samples (mono: = frames).
 */
class PassengerFill(private val chunk: Int, private val capacity: Int, holdSamples: Int) {
    enum class Verdict { WRITE, FULL, TRIM }

    val hold = GrowOnUnderrun(holdSamples, chunk, capacity)
    var full = 0L
        private set
    var trimmed = 0L
        private set
    /** Chunks whose fill could not be told. */
    var unknown = 0L
        private set
    var minFill = Int.MAX_VALUE
        private set
    var maxFill = 0
        private set
    private var fillSum = 0L
    private var fills = 0L
    private var windowMin = Int.MAX_VALUE
    private var windowChunks = 0

    val meanFill: Int get() = if (fills == 0L) 0 else (fillSum / fills).toInt()

    /** [fill] = samples written and not yet played, before this chunk. */
    fun admit(fill: Int): Verdict {
        if (fill < 0 || fill > capacity) {
            unknown++
            return Verdict.WRITE
        }
        fillSum += fill
        fills++
        if (fill < minFill) minFill = fill
        if (fill > maxFill) maxFill = fill
        if (fill < windowMin) windowMin = fill
        var trim = false
        if (++windowChunks >= WINDOW) {
            trim = windowMin >= hold.size + chunk
            windowChunks = 0
            windowMin = Int.MAX_VALUE
        }
        return when {
            capacity - fill < chunk -> Verdict.FULL.also { full++ }
            trim -> Verdict.TRIM.also { trimmed++ }
            else -> Verdict.WRITE
        }
    }

    companion object {
        /** Chunks per window (1 s). */
        const val WINDOW = 50

        /** Samples written minus the track's 32-bit wrapping play position. */
        fun fill(written: Long, headPosition: Int): Int {
            val d = (written - (headPosition.toLong() and 0xffffffffL)) and 0xffffffffL
            return if (d > Int.MAX_VALUE) -1 else d.toInt()
        }
    }
}
