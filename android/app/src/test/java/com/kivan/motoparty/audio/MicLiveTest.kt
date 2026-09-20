package com.kivan.motoparty.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MicLive] is the F9a condition (c) of the live earcon: the headset's **own** mic signal is
 * arriving, i.e. the recorder is routed to a Bluetooth SCO input and, after that moment,
 * [MicLive.FRAMES_NEEDED] consecutive 20 ms frames are not digital silence. The 2026-09-20 device
 * run showed capture starting on the built-in mic and being moved to the SCO input ~0.1 s later, so
 * level before that moment says nothing about the headset.
 */
class MicLiveTest {
    private val mic = MicLive()

    /** Feed [n] frames of [peak] from [fromMs], 20 ms apart; returns the first fire, if any. */
    private fun feed(n: Int, peak: Int, fromMs: Long): Long? {
        var fired: Long? = null
        for (i in 0 until n) {
            val at = fromMs + i * 20L
            mic.frame(peak, at)?.let { if (fired == null) fired = it }
        }
        return fired
    }

    @Test
    fun `silence on the SCO input and then signal`() {
        mic.routedToSco(140)
        assertNull("digital silence is not the headset talking", feed(20, 0, 140))
        assertFalse(mic.isLive)
        // 10 frames = 200 ms of signal, so the 10th (at 540 + 9*20) is the one.
        assertNull(feed(9, 900, 540))
        assertEquals(720L, mic.frame(900, 720))
        assertTrue(mic.isLive)
        assertEquals(720L, mic.liveAtMs)
        assertEquals(140L, mic.scoRoutedAtMs)
    }

    @Test
    fun `signal before the SCO moment does not count`() {
        // The built-in mic delivered the rider's noise for 200 ms before the route moved.
        assertNull("nothing counts before the recorder is on the SCO input", feed(10, 4_000, 0))
        assertNull(mic.scoRoutedAtMs)
        mic.routedToSco(200)
        // Frames older than that moment are still not the headset's, however loud.
        assertNull(feed(10, 4_000, 0))
        assertNull(feed(9, 4_000, 200))
        assertEquals(380L, mic.frame(4_000, 380))
    }

    @Test
    fun `one silent frame restarts the count`() {
        mic.routedToSco(0)
        assertNull(feed(9, 500, 0))
        assertNull("a gap means it was not the headset's stream", mic.frame(0, 180))
        assertNull(feed(9, 500, 200))
        assertEquals(380L, mic.frame(500, 380))
    }

    @Test
    fun `a peak at the threshold is still silence`() {
        mic.routedToSco(0)
        assertNull("the threshold itself does not count as signal", feed(40, MicLive.PEAK_THRESHOLD, 0))
        assertNull(feed(9, MicLive.PEAK_THRESHOLD + 1, 1_000))
        assertEquals(1_180L, mic.frame(MicLive.PEAK_THRESHOLD + 1, 1_180))
    }

    @Test
    fun `it fires once, and a later flap changes nothing`() {
        mic.routedToSco(0)
        assertEquals(180L, feed(10, 900, 0))
        assertNull("one report per engine", feed(10, 900, 200))
        // After the mic was established, route churn is LiveCue's business, not this detector's.
        mic.routedElsewhere()
        assertTrue(mic.isLive)
        assertEquals(180L, mic.liveAtMs)
        assertEquals(0L, mic.scoRoutedAtMs)
        assertNull(feed(10, 900, 400))
    }

    @Test
    fun `a route that moves away before the mic was live starts over`() {
        mic.routedToSco(100)
        assertNull(feed(5, 900, 100))
        mic.routedElsewhere() // the SCO input is gone again: whatever arrives now is not the headset
        assertNull(mic.scoRoutedAtMs)
        assertNull(feed(20, 900, 200))
        mic.routedToSco(600)
        assertNull(feed(9, 900, 600))
        assertEquals(780L, mic.frame(900, 780))
    }

    @Test
    fun `reset makes it a new engine`() {
        mic.routedToSco(0)
        assertEquals(180L, feed(10, 900, 0))
        mic.reset()
        assertFalse(mic.isLive)
        assertNull(mic.liveAtMs)
        assertNull(mic.scoRoutedAtMs)
        assertNull("and the new engine waits for its own SCO input", feed(10, 900, 1_000))
        mic.routedToSco(1_200)
        assertEquals(1_380L, feed(10, 900, 1_200))
    }

    @Test
    fun `the run length and threshold are settable for tuning`() {
        val strict = MicLive(framesNeeded = 3, peakThreshold = 500)
        strict.routedToSco(0)
        assertNull(strict.frame(500, 0))
        assertNull(strict.frame(501, 20))
        assertNull(strict.frame(501, 40))
        assertEquals(60L, strict.frame(501, 60))
    }
}
