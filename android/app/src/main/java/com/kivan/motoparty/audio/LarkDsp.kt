package com.kivan.motoparty.audio

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The host-mic talk's signal path (PROTOCOL.md "Host-mic talk"), all pure JVM and clock-free, so
 * every piece is unit-tested. The capture thread ([LarkEngine]) owns one [LarkPipeline] per talk
 * and calls it once per 20 ms; nothing here allocates after construction.
 *
 * Samples are carried as floats in 16-bit units (−32768..32767) between the stages, so a stage never
 * rescales and the conversion back to PCM16 is one rounding and a clip.
 */

/**
 * A 2nd-order Butterworth high-pass (RBJ biquad, Q = 1/√2), in place, state kept across calls.
 * At [HighPass.WIND_HZ] it takes out the wind and engine rumble under a helmet, where most of
 * their energy is, before the encoder spends bits on it (wired-mic plan, Stage C).
 */
class HighPass(sampleRate: Int, cutoffHz: Double = WIND_HZ) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    // Transposed direct form II: two state values, good numerically at low cutoffs.
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val w0 = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val c = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + c) / 2 / a0
        b1 = -(1 + c) / a0
        b2 = (1 + c) / 2 / a0
        a1 = -2 * c / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: FloatArray, n: Int = x.size) {
        for (i in 0 until n) {
            val v = x[i].toDouble()
            val y = b0 * v + z1
            z1 = b1 * v - a1 * y + z2
            z2 = b2 * v - a2 * y
            x[i] = y.toFloat()
        }
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        const val WIND_HZ = 150.0
    }
}

/**
 * 48 kHz → 16 kHz: a linear-phase FIR low-pass (Kaiser-windowed sinc, [TAPS] taps, −6 dB at
 * [CUTOFF_HZ], > 80 dB down from 7 kHz) evaluated only at every third input sample. Without the
 * low-pass, everything the Lark delivers between 8 and 24 kHz — wind hiss above all — would fold
 * back into the voice band. Group delay (TAPS − 1) / 2 = 63 input samples, 1.3 ms.
 *
 * Streaming: the last TAPS − 1 input samples are carried into the next call, so a stream split into
 * frames gives exactly what one long call would. [process] takes whole multiples of [FACTOR].
 */
class Decimator(private val maxInput: Int) {
    private val h = design()
    /** [TAPS] − 1 samples of history, then the new input. */
    private val buf = FloatArray(TAPS - 1 + maxInput)

    /** Filters [n] samples of [input] (a multiple of [FACTOR], at most maxInput) into [out]; returns n / FACTOR. */
    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        require(n % FACTOR == 0 && n <= maxInput) { "bad block $n" }
        System.arraycopy(input, 0, buf, TAPS - 1, n)
        val outN = n / FACTOR
        for (k in 0 until outN) {
            // Newest sample of this output's group is at TAPS - 1 + FACTOR * k + (FACTOR - 1).
            val p = TAPS - 1 + FACTOR * k + (FACTOR - 1)
            var acc = 0f
            for (j in 0 until TAPS) acc += h[j] * buf[p - j]
            out[k] = acc
        }
        System.arraycopy(buf, n, buf, 0, TAPS - 1)
        return outN
    }

    fun reset() = buf.fill(0f)

    companion object {
        const val FACTOR = 3
        const val TAPS = 127
        const val CUTOFF_HZ = 6_000.0
        private const val RATE_IN = 48_000.0
        private const val BETA = 8.0

        private fun design(): FloatArray {
            val m = TAPS - 1
            val fc = CUTOFF_HZ / RATE_IN
            val raw = DoubleArray(TAPS) { i ->
                val n = i - m / 2.0
                val x = 2 * fc * n
                val sinc = if (n == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val r = 2.0 * i / m - 1
                2 * fc * sinc * besselI0(BETA * sqrt(1 - r * r)) / besselI0(BETA)
            }
            val sum = raw.sum() // unity gain at DC
            return FloatArray(TAPS) { (raw[it] / sum).toFloat() }
        }

        /** Modified Bessel function of the first kind, order 0 (series; converges fast for β ≤ 10). */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                val q = x / (2 * k)
                term *= q * q
                sum += term
                k++
            }
            return sum
        }
    }
}

/** PCM16 ↔ float helpers for the pipeline. */
object Pcm {
    /**
     * Splits [frames] interleaved stereo frames of [src] into [left] and [right]; with [swap] the
     * channels trade places (the "stickers mixed up" setting).
     */
    fun deinterleave(src: ShortArray, frames: Int, left: FloatArray, right: FloatArray, swap: Boolean = false) {
        val l = if (swap) right else left
        val r = if (swap) left else right
        for (i in 0 until frames) {
            l[i] = src[2 * i].toFloat()
            r[i] = src[2 * i + 1].toFloat()
        }
    }

    /** Rounds and clips [n] samples to PCM16. */
    fun toPcm16(src: FloatArray, n: Int, dst: ShortArray) {
        for (i in 0 until n) {
            val v = src[i].roundToInt()
            dst[i] = (if (v > Short.MAX_VALUE) Short.MAX_VALUE.toInt() else if (v < Short.MIN_VALUE) Short.MIN_VALUE.toInt() else v).toShort()
        }
    }
}

/**
 * One 20 ms frame of the Lark receiver in, the talk's three signals out:
 *
 *     48 kHz stereo ─ split (+swap) ─ high-pass both ─┬ rider ─ decimate ─ [rider16]  → encoder (+ ASR if the host opened)
 *                                                    └ passenger ────── [passenger48] → local playback
 *                                                                 └ decimate ─ [passenger16] → ASR if the client opened
 *
 * Left is the rider (Mic1, pink), right the passenger (Mic2, yellow), unless [swap]. The raw frame
 * goes to the capture dump before this, untouched. Nobody hears their own voice: the rider's
 * channel is only sent, the passenger's only played here (PROTOCOL.md "Host-mic talk").
 */
class LarkPipeline(private val swap: Boolean, private val rateIn: Int = RATE_IN) {
    private val frames = rateIn / 50
    private val rider = FloatArray(frames)
    private val passenger = FloatArray(frames)
    private val out16 = FloatArray(frames / Decimator.FACTOR)
    private val riderHp = HighPass(rateIn)
    private val passengerHp = HighPass(rateIn)
    private val riderDown = Decimator(frames)
    private val passengerDown = Decimator(frames)

    /** The rider, 16 kHz mono, 320 samples: the talk's voice as sent. */
    val rider16 = ShortArray(frames / Decimator.FACTOR)
    /** The passenger, 48 kHz mono, 960 samples: played into the rider's headset. */
    val passenger48 = ShortArray(frames)
    /** The passenger, 16 kHz mono, 320 samples: filled only when asked for (ASR on the passenger). */
    val passenger16 = ShortArray(frames / Decimator.FACTOR)

    /** Interleaved stereo samples per frame: 1920. */
    val frameSamples: Int get() = frames * 2

    /**
     * [stereo]: one frame, [frameSamples] interleaved samples. [passengerDown16]: also fill
     * [passenger16] (its decimator runs only then; switching it on mid-talk costs a 1.3 ms settle).
     */
    fun process(stereo: ShortArray, passengerDown16: Boolean) {
        require(stereo.size >= frames * 2) { "short frame ${stereo.size}" }
        Pcm.deinterleave(stereo, frames, rider, passenger, swap)
        riderHp.process(rider, frames)
        passengerHp.process(passenger, frames)
        riderDown.process(rider, frames, out16)
        Pcm.toPcm16(out16, out16.size, rider16)
        Pcm.toPcm16(passenger, frames, passenger48)
        if (passengerDown16) {
            passengerDown.process(passenger, frames, out16)
            Pcm.toPcm16(out16, out16.size, passenger16)
        }
    }

    companion object {
        const val RATE_IN = 48_000
    }
}

/**
 * Levels of the two raw channels over a talk, for the `lark stats:` line: RMS in dBFS and the
 * largest sample. A transmitter that is off reads as exact digital zeros on its channel, so
 * `rms -inf peak 0` means "that TX was off", not "quiet". Pure; the capture thread feeds it.
 */
class LarkLevels {
    private var sumL = 0.0
    private var sumR = 0.0
    var peakL = 0
        private set
    var peakR = 0
        private set
    var frames = 0L
        private set

    /** [n] interleaved stereo frames of raw capture. */
    fun add(stereo: ShortArray, n: Int) {
        for (i in 0 until n) {
            val l = stereo[2 * i].toInt()
            val r = stereo[2 * i + 1].toInt()
            sumL += (l * l).toDouble()
            sumR += (r * r).toDouble()
            val al = if (l < 0) -l else l
            val ar = if (r < 0) -r else r
            if (al > peakL) peakL = al
            if (ar > peakR) peakR = ar
        }
        frames += n
    }

    fun rmsDbfsL(): Double = dbfs(sumL)
    fun rmsDbfsR(): Double = dbfs(sumR)

    companion object {
        /** The receiver's idle floor is ±2–7 LSB; speech, breath or wind is hundreds and more. */
        const val SILENT_PEAK = 32
        const val SILENT_MIN_S = 3
    }

    private fun dbfs(sum: Double): Double =
        if (frames == 0L || sum == 0.0) Double.NEGATIVE_INFINITY else 20 * log10(sqrt(sum / frames) / 32_768.0)

    /**
     * One line per channel that carried nothing all talk — every sample within ±[SILENT_PEAK], the
     * receiver's idle floor — over a talk of at least [SILENT_MIN_S] s, else null. 2026-09-29,
     * session 8: 25 s of the left channel at peak 7 (±2 LSB) went out as the rider's voice, and the
     * passenger heard nothing; this says so instead of leaving it to a WAV. With [swap] off the
     * left is the rider.
     *
     * `lark: left channel (rider) silent all talk, peak 7 over 25.0 s: that TX sent nothing (muted, asleep, off or out of range?)`
     */
    fun silentLines(rate: Int, swap: Boolean): List<String> {
        if (frames < rate.toLong() * SILENT_MIN_S) return emptyList()
        val seconds = "%.1f".format(Locale.US, frames.toDouble() / rate)
        fun line(side: String, role: String, peak: Int) =
            "lark: $side channel ($role) silent all talk, peak $peak over $seconds s: " +
                "that TX sent nothing (muted, asleep, off or out of range?)"
        return buildList {
            if (peakL <= SILENT_PEAK) add(line("left", if (swap) "passenger" else "rider", peakL))
            if (peakR <= SILENT_PEAK) add(line("right", if (swap) "rider" else "passenger", peakR))
        }
    }

    /**
     * `lark stats: 12.3 s, L rms -31.2 dBFS peak 20114, R rms -inf dBFS peak 0, sent 402 frames,
     * played 615, dropped 0, client audio dropped 0`.
     */
    fun line(rate: Int, sent: Long, played: Long, dropped: Long, clientDropped: Long): String {
        fun db(v: Double) = if (v == Double.NEGATIVE_INFINITY) "-inf" else "%.1f".format(Locale.US, v)
        val seconds = "%.1f".format(Locale.US, frames.toDouble() / rate)
        return "lark stats: $seconds s, L rms ${db(rmsDbfsL())} dBFS peak $peakL, R rms ${db(rmsDbfsR())} dBFS peak $peakR, " +
            "sent $sent frames, played $played, dropped $dropped, client audio dropped $clientDropped"
    }
}
