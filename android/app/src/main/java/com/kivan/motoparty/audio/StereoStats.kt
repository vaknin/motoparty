package com.kivan.motoparty.audio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Does an interleaved 16-bit stereo capture carry two different microphones, or one copied twice?
 * Gate S4 of `research/MIC.md` §6: one Lark A1 Duo in Stereo mode puts the rider (TX1) on L and the
 * passenger (TX2) on R, and the question is whether the Pixel hands an app both, or downmixes.
 *
 * The test the gate needs is the one the laptop's `lr.py` answered S2 and S3 with: cut the clip
 * into half-second windows and look for **both** kinds of window — some where L is clearly louder
 * (TX1 was talking) and some where R is (TX2 was). A downmix has L = R in every window; a capture
 * that only carries the left mic has no R-louder window. Whole-clip correlation and the share of
 * bit-identical samples ride along, because "L and R are bit-identical" is how Mono mode looked on
 * the laptop.
 *
 * Pure: no Android, no I/O, and [add] does not allocate, so the probe calls it on its read loop.
 */
class StereoStats(sampleRate: Int) {
    private val windowFrames = sampleRate / 2

    private var frames = 0L
    private var identical = 0L
    private var sumL = 0.0
    private var sumR = 0.0
    private var sumLR = 0.0
    private var peakL = 0
    private var peakR = 0

    private var winFrames = 0
    private var winL = 0.0
    private var winR = 0.0

    /** Half-second windows where one channel was louder by [DOMINANT_DB], above [SPEECH_DBFS]. */
    var leftWindows = 0
        private set
    var rightWindows = 0
        private set

    /** [count] interleaved samples of [pcm] (L,R,L,R…); a trailing odd sample is ignored. */
    fun add(pcm: ShortArray, count: Int = pcm.size) {
        var i = 0
        while (i + 1 < count) {
            val l = pcm[i].toInt()
            val r = pcm[i + 1].toInt()
            i += 2
            frames++
            if (l == r) identical++
            val ld = l.toDouble()
            val rd = r.toDouble()
            sumL += ld * ld
            sumR += rd * rd
            sumLR += ld * rd
            peakL = max(peakL, abs(l))
            peakR = max(peakR, abs(r))
            winL += ld * ld
            winR += rd * rd
            if (++winFrames == windowFrames) closeWindow()
        }
    }

    private fun closeWindow() {
        val l = dbfs(winL, winFrames.toLong())
        val r = dbfs(winR, winFrames.toLong())
        if (max(l, r) >= SPEECH_DBFS) {
            if (l - r >= DOMINANT_DB) leftWindows++
            if (r - l >= DOMINANT_DB) rightWindows++
        }
        winFrames = 0
        winL = 0.0
        winR = 0.0
    }

    /** Pearson-style, about zero (a mic's DC offset is negligible here); 0 when a channel is silent. */
    val correlation: Double
        get() = if (sumL == 0.0 || sumR == 0.0) 0.0 else sumLR / sqrt(sumL * sumR)

    /** The share of frames where L and R are the same sample exactly. */
    val identicalShare: Double
        get() = if (frames == 0L) 0.0 else identical.toDouble() / frames

    /** The gate: each mic had its own channel at least twice (TX1 once, TX2 once, a few words each). */
    val twoChannels: Boolean
        get() = leftWindows >= MIN_WINDOWS && rightWindows >= MIN_WINDOWS

    /**
     * One line for the log, e.g.
     * `L -24.1 dBFS, R -27.9 dBFS, corr 0.04, identical 0 %, peak L 0.98 R 0.61, windows L>R 7, R>L 6 → TWO CHANNELS`.
     */
    fun line(): String {
        val verdict = when {
            twoChannels -> "TWO CHANNELS"
            identicalShare > 0.99 -> "one channel (L = R)"
            else -> "not two channels"
        }
        return String.format(
            Locale.US, // the bench reads this line; a comma decimal separator is not a number to it
            "L %.1f dBFS, R %.1f dBFS, corr %.2f, identical %.0f %%, peak L %.2f R %.2f, windows L>R %d, R>L %d → %s",
            dbfs(sumL, frames), dbfs(sumR, frames), correlation, identicalShare * 100,
            peakL / FULL_SCALE, peakR / FULL_SCALE, leftWindows, rightWindows, verdict,
        )
    }

    companion object {
        /** A window only counts when its louder channel is at least this loud: speech, not hiss. */
        const val SPEECH_DBFS = -45.0

        /** S2 separated 21–47 dB and S3 17–32 dB; 10 dB is well clear of a downmix's 0. */
        const val DOMINANT_DB = 10.0

        const val MIN_WINDOWS = 2

        private const val FULL_SCALE = 32_768.0

        private fun dbfs(sumSquares: Double, n: Long): Double =
            if (n == 0L || sumSquares == 0.0) -120.0 else 20 * log10(sqrt(sumSquares / n) / FULL_SCALE)
    }
}
