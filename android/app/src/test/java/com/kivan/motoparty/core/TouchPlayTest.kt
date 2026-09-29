package com.kivan.motoparty.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md "Browsing" step 3: only a `now` enqueue (and a jump) ends a talk. */
class TouchPlayTest {
    @Test
    fun onlyPlayNowEndsATalk() {
        assertTrue(TouchPlay.enqueueEndsTalk(EnqueueMode.NOW))
        assertFalse(TouchPlay.enqueueEndsTalk(EnqueueMode.NEXT))
        assertFalse(TouchPlay.enqueueEndsTalk(EnqueueMode.END))
    }
}
