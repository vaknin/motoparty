package com.kivan.motoparty.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The timing lines are read off logcat by hand and by the bench; pin their shape. */
class StepTimerTest {
    @Test
    fun lineListsEachStepAndTheTotal() {
        var now = 0L
        val t = StepTimer { now }
        now += 4_000_000; t.step("setMode")
        now += 2_400_000; t.step("devices")
        now += 290_000_000; t.step("setCommunicationDevice")
        now += 16_000_000
        assertEquals("enterCall 312 ms (setMode 4, devices 2, setCommunicationDevice 290)", t.line("enterCall"))
    }

    @Test
    fun noStepsIsJustTheTotal() {
        var now = 0L
        val t = StepTimer { now }
        now += 40_000_000
        assertEquals("start 40 ms", t.line("start"))
    }
}
