package com.kivan.motoparty.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MicLevelTest {
    @Test
    fun peakIsTheLoudestSampleEitherSign() {
        assertEquals(0, MicLevel.peakOf(ShortArray(320)))
        assertEquals(1200, MicLevel.peakOf(shortArrayOf(3, -1200, 900)))
        // -32768 has no positive short: it must not wrap to a negative peak.
        assertEquals(32768, MicLevel.peakOf(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)))
    }
}
