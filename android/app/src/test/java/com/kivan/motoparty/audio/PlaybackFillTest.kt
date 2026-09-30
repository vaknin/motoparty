package com.kivan.motoparty.audio

import com.kivan.motoparty.audio.PassengerFill.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFillTest {
    @Test
    fun growsOneStepPerRiseAndStopsAtMax() {
        val g = GrowOnUnderrun(start = 640, step = 320, max = 1200)
        assertNull(g.underruns(0))
        assertEquals(960, g.underruns(1))
        assertNull(g.underruns(1)) // no new underrun
        assertEquals(1200, g.underruns(4)) // several since the last look: still one step, capped
        assertNull(g.underruns(9))
        assertEquals(2, g.grown)
    }

    @Test
    fun startAboveMaxIsMaxAndStartUpUnderrunsDoNotCount() {
        val g = GrowOnUnderrun(start = 640, step = 320, max = 500)
        assertEquals(500, g.size)
        val h = GrowOnUnderrun(start = 640, step = 320, max = 2000)
        h.baseline(2)
        assertNull(h.underruns(2))
        assertEquals(960, h.underruns(3))
    }

    @Test
    fun aChunkThatDoesNotFitWholeIsDroppedWhole() {
        val f = PassengerFill(chunk = 960, capacity = 2880, holdSamples = 1920)
        assertEquals(Verdict.WRITE, f.admit(1920)) // exactly one chunk free
        assertEquals(Verdict.FULL, f.admit(1921))
        assertEquals(Verdict.FULL, f.admit(2880))
        assertEquals(2L, f.full)
    }

    @Test
    fun trimsOneChunkPerWindowWhileTheSmallestFillStaysAboveTheHold() {
        val f = PassengerFill(chunk = 960, capacity = 9600, holdSamples = 1920)
        // Parked 100 ms deep: the 50th chunk of each window is trimmed, nothing else.
        val verdicts = List(100) { f.admit(4800) }
        assertEquals(listOf(49, 99), verdicts.indices.filter { verdicts[it] == Verdict.TRIM })
        // One dip to the hold inside a window: that depth is needed, no trim.
        val dip = List(50) { f.admit(if (it == 10) 1920 else 4800) }
        assertEquals(0, dip.count { it == Verdict.TRIM })
        // Less than a chunk above the hold: trimming would go below it.
        assertEquals(0, List(50) { f.admit(2879) }.count { it == Verdict.TRIM })
        assertEquals(1, List(50) { f.admit(2880) }.count { it == Verdict.TRIM })
        assertEquals(3L, f.trimmed)
    }

    @Test
    fun holdGrownToTheCapacityNeverTrims() {
        val f = PassengerFill(chunk = 960, capacity = 2880, holdSamples = 1920)
        assertEquals(2880, f.hold.underruns(1))
        assertEquals(0, List(200) { f.admit(1920) }.count { it == Verdict.TRIM })
    }

    @Test
    fun unknownFillWritesAndStaysOutOfTheStats() {
        val f = PassengerFill(chunk = 960, capacity = 2880, holdSamples = 1920)
        assertEquals(Verdict.WRITE, f.admit(-1))
        assertEquals(Verdict.WRITE, f.admit(5000))
        assertEquals(2L, f.unknown)
        assertEquals(0, f.meanFill)
        f.admit(960); f.admit(1920)
        assertEquals(listOf(960, 1440, 1920), listOf(f.minFill, f.meanFill, f.maxFill))
    }

    @Test
    fun fillSurvivesThePositionWrap() {
        assertEquals(960, PassengerFill.fill(written = 5760, headPosition = 4800))
        // The play position is a wrapping 32-bit counter; `written` keeps counting.
        assertEquals(960, PassengerFill.fill(written = (1L shl 32) + 100, headPosition = (0xffffffffL - 859).toInt()))
        assertEquals(-1, PassengerFill.fill(written = 0, headPosition = 1)) // ahead of what was written
    }
}
