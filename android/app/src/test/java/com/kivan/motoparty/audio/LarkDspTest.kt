package com.kivan.motoparty.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The host-mic talk's signal path ([HighPass], [Decimator], [Pcm], [LarkPipeline], [LarkLevels]).
 * Gains are measured on steady-state tones: the first part of each run is thrown away so the
 * filters' start-up transient does not count.
 */
class LarkDspTest {
    private fun tone(hz: Double, rate: Int, n: Int, amp: Double = 10_000.0) =
        FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i].toDouble() * x[i]
        return sqrt(s / (x.size - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    /** Gain in dB of the high-pass at [hz], 48 kHz, after 0.5 s of settling. */
    private fun highPassGain(hz: Double): Double {
        val x = tone(hz, 48_000, 48_000)
        val ref = rms(x, 24_000)
        HighPass(48_000).process(x)
        return db(rms(x, 24_000) / ref)
    }

    /** Gain in dB of the decimator at [hz], fed in 20 ms blocks, as the capture loop does. */
    private fun decimatorGain(hz: Double): Double {
        val d = Decimator(960)
        val x = tone(hz, 48_000, 48_000)
        val out = FloatArray(16_000)
        val block = FloatArray(960)
        val o = FloatArray(320)
        for (b in 0 until 50) {
            System.arraycopy(x, b * 960, block, 0, 960)
            assertEquals(320, d.process(block, 960, o))
            System.arraycopy(o, 0, out, b * 320, 320)
        }
        return db(rms(out, 1_600) / rms(x, 4_800))
    }

    @Test
    fun `high-pass takes out rumble and keeps the voice`() {
        assertTrue("50 Hz ${highPassGain(50.0)}", highPassGain(50.0) < -18)
        assertTrue("100 Hz ${highPassGain(100.0)}", highPassGain(100.0) < -6)
        assertEquals("150 Hz is the -3 dB corner", -3.0, highPassGain(150.0), 0.3)
        assertEquals(0.0, highPassGain(1_000.0), 0.1)
        assertEquals(0.0, highPassGain(4_000.0), 0.1)
        // DC (a mic bias offset) is gone.
        val dc = FloatArray(48_000) { 5_000f }
        HighPass(48_000).process(dc)
        assertTrue(rms(dc, 24_000) < 1.0)
    }

    @Test
    fun `decimator passes speech and stops what would alias`() {
        assertEquals(0.0, decimatorGain(1_000.0), 0.1)
        assertEquals(0.0, decimatorGain(3_000.0), 0.1)
        assertTrue("5 kHz ${decimatorGain(5_000.0)}", decimatorGain(5_000.0) > -1.0)
        for (hz in listOf(7_000.0, 8_000.0, 9_000.0, 10_000.0, 15_000.0)) {
            assertTrue("$hz Hz ${decimatorGain(hz)}", decimatorGain(hz) < -60)
        }
    }

    @Test
    fun `decimator is the same in blocks as in one go`() {
        val x = tone(440.0, 48_000, 2_880).also { for (i in it.indices) it[i] += (i % 7) * 100f }
        val whole = FloatArray(960)
        Decimator(2_880).process(x, 2_880, whole)
        val d = Decimator(960)
        val parts = FloatArray(960)
        val block = FloatArray(960)
        val o = FloatArray(320)
        for (b in 0 until 3) {
            System.arraycopy(x, b * 960, block, 0, 960)
            d.process(block, 960, o)
            System.arraycopy(o, 0, parts, b * 320, 320)
        }
        for (i in whole.indices) assertEquals(whole[i], parts[i], 0.01f)
    }

    @Test
    fun `deinterleave, swap and clip`() {
        val src = shortArrayOf(1, -1, 2, -2, 3, -3)
        val l = FloatArray(3)
        val r = FloatArray(3)
        Pcm.deinterleave(src, 3, l, r)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), l, 0f)
        assertArrayEquals(floatArrayOf(-1f, -2f, -3f), r, 0f)
        Pcm.deinterleave(src, 3, l, r, swap = true)
        assertArrayEquals(floatArrayOf(-1f, -2f, -3f), l, 0f)
        val out = ShortArray(4)
        Pcm.toPcm16(floatArrayOf(40_000f, -40_000f, 1.6f, -1.4f), 4, out)
        assertArrayEquals(shortArrayOf(32_767, -32_768, 2, -1), out)
    }

    /** One second of 20 ms stereo frames: [left] Hz on L, [right] Hz on R (0 = digital silence). */
    private fun run(p: LarkPipeline, left: Double, right: Double, passenger16: Boolean): Triple<ShortArray, ShortArray, ShortArray> {
        val rider = ShortArray(16_000)
        val pass48 = ShortArray(48_000)
        val pass16 = ShortArray(16_000)
        val frame = ShortArray(p.frameSamples)
        assertEquals(1_920, p.frameSamples)
        for (f in 0 until 50) {
            for (i in 0 until 960) {
                val n = f * 960 + i
                frame[2 * i] = if (left == 0.0) 0 else (8_000 * sin(2 * PI * left * n / 48_000)).toInt().toShort()
                frame[2 * i + 1] = if (right == 0.0) 0 else (8_000 * sin(2 * PI * right * n / 48_000)).toInt().toShort()
            }
            p.process(frame, passenger16)
            assertEquals(320, p.rider16.size)
            assertEquals(960, p.passenger48.size)
            System.arraycopy(p.rider16, 0, rider, f * 320, 320)
            System.arraycopy(p.passenger48, 0, pass48, f * 960, 960)
            System.arraycopy(p.passenger16, 0, pass16, f * 320, 320)
        }
        return Triple(rider, pass48, pass16)
    }

    private fun rms(x: ShortArray, from: Int) = rms(FloatArray(x.size) { x[it].toFloat() }, from)

    @Test
    fun `rider goes to the encoder, passenger to playback, and nobody crosses`() {
        val (rider, pass48, pass16) = run(LarkPipeline(swap = false), left = 1_000.0, right = 0.0, passenger16 = true)
        assertEquals(8_000 / sqrt(2.0), rms(rider, 1_600), 100.0)
        assertEquals(0.0, rms(pass48, 4_800), 0.0)
        assertEquals(0.0, rms(pass16, 1_600), 0.0)
    }

    @Test
    fun `swap puts the left channel on the passenger`() {
        val (rider, pass48, pass16) = run(LarkPipeline(swap = true), left = 1_000.0, right = 0.0, passenger16 = true)
        assertEquals(0.0, rms(rider, 1_600), 0.0)
        assertEquals(8_000 / sqrt(2.0), rms(pass48, 4_800), 100.0)
        assertEquals(8_000 / sqrt(2.0), rms(pass16, 1_600), 100.0)
    }

    @Test
    fun `the passenger is decimated only when the recognizer wants it`() {
        val (_, pass48, pass16) = run(LarkPipeline(swap = false), left = 0.0, right = 700.0, passenger16 = false)
        assertTrue(rms(pass48, 4_800) > 5_000)
        assertEquals(0.0, rms(pass16, 0), 0.0)
    }

    @Test
    fun `levels - a TX that is off reads as exact zeros`() {
        val lv = LarkLevels()
        val frame = ShortArray(1_920) { if (it % 2 == 0) (if (it % 4 == 0) 16_384 else -16_384).toShort() else 0 }
        repeat(50) { lv.add(frame, 960) }
        assertEquals(48_000L, lv.frames)
        assertEquals(16_384, lv.peakL)
        assertEquals(0, lv.peakR)
        assertEquals(-6.0, lv.rmsDbfsL(), 0.1)
        assertEquals(
            "lark stats: 1.0 s, L rms -6.0 dBFS peak 16384, R rms -inf dBFS peak 0, sent 40 frames, played 49, dropped 1, client audio dropped 3",
            lv.line(48_000, sent = 40, played = 49, dropped = 1, clientDropped = 3),
        )
    }

    @Test
    fun `a channel at the idle floor all talk is called out, a live one is not`() {
        val lv = LarkLevels()
        // 25 s: left at ±2 (session 8's rider), right with a voice in it.
        val frame = ShortArray(960 * 2) { i -> if (i % 2 == 0) (if (i % 4 == 0) 2 else -2).toShort() else (if (i % 8 == 1) 3000 else 0).toShort() }
        repeat(25 * 50) { lv.add(frame, 960) }
        assertEquals(
            listOf("lark: left channel (rider) silent all talk, peak 2 over 25.0 s: that TX sent nothing (muted, asleep, off or out of range?)"),
            lv.silentLines(48_000, swap = false),
        )
        assertEquals("swapped, the left is the passenger", true, lv.silentLines(48_000, swap = true).single().contains("left channel (passenger)"))
    }

    @Test
    fun `a talk too short to judge says nothing`() {
        val lv = LarkLevels()
        repeat(50) { lv.add(ShortArray(960 * 2), 960) }
        assertEquals(emptyList<String>(), lv.silentLines(48_000, swap = false))
    }
}
