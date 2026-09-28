package com.kivan.motoparty.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [StereoStats] is the S4 verdict: two mics on two channels, or one mic copied. The clips below are
 * the shapes the laptop saw on the real receiver — Mono mode (L and R bit-identical), Stereo mode
 * (TX1 on L, TX2 on R) — plus the Pixel 7 report's failure, where an app only got the left mic.
 */
class StereoStatsTest {
    private val rate = 48_000

    /** A 440 Hz tone at [amp] of full scale, [seconds] long, as mono samples. */
    private fun tone(seconds: Double, amp: Double): ShortArray =
        ShortArray((rate * seconds).toInt()) { (sin(2 * PI * 440 * it / rate) * amp * 32_767).toInt().toShort() }

    private fun silence(seconds: Double) = ShortArray((rate * seconds).toInt())

    /** Interleave two mono signals of the same length. */
    private fun stereo(l: ShortArray, r: ShortArray) = ShortArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else r[it / 2] }

    private fun stats(vararg clips: ShortArray) = StereoStats(rate).apply { clips.forEach { add(it) } }

    @Test
    fun `stereo mode, rider then passenger, is two channels`() {
        val riderTalks = stereo(tone(2.0, 0.3), tone(2.0, 0.003)) // ~40 dB apart, as S2 measured
        val passengerTalks = stereo(tone(2.0, 0.003), tone(2.0, 0.3))
        val s = stats(riderTalks, passengerTalks)
        assertEquals(4, s.leftWindows)
        assertEquals(4, s.rightWindows)
        assertTrue(s.twoChannels)
        assertTrue(s.line(), s.line().endsWith("→ TWO CHANNELS"))
    }

    @Test
    fun `mono mode, the same voice on both sides bit for bit, is one channel`() {
        val voice = tone(4.0, 0.3)
        val s = stats(stereo(voice, voice))
        assertFalse(s.twoChannels)
        assertEquals(1.0, s.identicalShare, 0.0)
        assertEquals(1.0, s.correlation, 1e-9)
        assertTrue(s.line(), s.line().endsWith("→ one channel (L = R)"))
    }

    @Test
    fun `only the left mic reaching the app is not two channels`() {
        val s = stats(stereo(tone(2.0, 0.3), silence(2.0)), stereo(silence(2.0), silence(2.0)))
        assertEquals(4, s.leftWindows)
        assertEquals(0, s.rightWindows)
        assertFalse(s.twoChannels)
        assertTrue(s.line(), s.line().endsWith("→ not two channels"))
    }

    @Test
    fun `hiss below speech level does not count as a mic talking`() {
        val s = stats(stereo(tone(2.0, 0.002), silence(2.0)), stereo(silence(2.0), tone(2.0, 0.002)))
        assertEquals("-54 dBFS is under the speech floor", 0, s.leftWindows + s.rightWindows)
        assertFalse(s.twoChannels)
    }

    @Test
    fun `windows carry across reads of any size`() {
        val clip = stereo(tone(1.0, 0.3), silence(1.0))
        val s = StereoStats(rate)
        // 20 ms frames as the probe reads them, and an odd tail that is not a whole frame.
        clip.toList().chunked(1920).forEach { s.add(it.toShortArray()) }
        assertEquals(2, s.leftWindows)
        s.add(ShortArray(3) { 100 }, 3)
        assertEquals(2, s.leftWindows)
    }

    @Test
    fun `the line is dot-decimal and names the levels`() {
        val s = stats(stereo(tone(1.0, 0.5), tone(1.0, 0.05)))
        val line = s.line()
        assertTrue(line, line.startsWith("L -9.0 dBFS, R -29.0 dBFS, corr 1.00, identical"))
        assertTrue(line, line.contains("peak L 0.50 R 0.05"))
    }
}
