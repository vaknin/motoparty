package com.kivan.motoparty.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landscape bug: the overlay stored raw pixels and restored them unclamped, so after a
 * rotation its frame was `[107,1550][421,2114]` on a 1 080 px tall screen — entirely below the
 * display, with nothing left on screen to drag. Numbers below are the real Pixel 8 ones:
 * 1 080 x 2 142 portrait, 2 142 x 1 080 landscape, window 314 x 564 px.
 */
class OverlayPlacementTest {
    private val portraitW = 1080
    private val portraitH = 2142
    private val landscapeW = 2142
    private val landscapeH = 1080
    private val winW = 314
    private val winH = 564

    @Test
    fun `a position that fits is kept exactly`() {
        assertEquals(107, OverlayPlacement.clamp(107, winW, portraitW))
        assertEquals(1550, OverlayPlacement.clamp(1550, winH, portraitH))
    }

    @Test
    fun `a position past the edge is pulled back inside`() {
        assertEquals(portraitW - winW, OverlayPlacement.clamp(5_000, winW, portraitW))
        assertEquals(0, OverlayPlacement.clamp(-40, winW, portraitW))
    }

    @Test
    fun `the portrait position that went off-screen survives the rotation`() {
        // The exact bug: y = 1550 in portrait, then the phone is rotated.
        val fy = OverlayPlacement.fraction(1550, winH, portraitH)
        val rotated = OverlayPlacement.position(fy, winH, landscapeH)
        assertTrue("y $rotated must leave the window fully on screen", rotated + winH <= landscapeH)
        assertTrue("y $rotated must not be negative", rotated >= 0)
    }

    @Test
    fun `a fraction round-trips on the same display`() {
        for (y in listOf(0, 1, 700, 1550, portraitH - winH)) {
            val back = OverlayPlacement.position(OverlayPlacement.fraction(y, winH, portraitH), winH, portraitH)
            assertTrue("$y round-tripped to $back", Math.abs(back - y) <= 1)
        }
    }

    @Test
    fun `the same fraction lands proportionally on both orientations`() {
        val f = 0.5f
        assertEquals((portraitH - winH) / 2, OverlayPlacement.position(f, winH, portraitH))
        assertEquals((landscapeH - winH) / 2, OverlayPlacement.position(f, winH, landscapeH))
        assertEquals((landscapeW - winW) / 2, OverlayPlacement.position(f, winW, landscapeW))
    }

    @Test
    fun `the edges stay the edges after a rotation`() {
        assertEquals(0, OverlayPlacement.position(0f, winH, landscapeH))
        assertEquals(landscapeH - winH, OverlayPlacement.position(1f, winH, landscapeH))
        assertEquals(landscapeW - winW, OverlayPlacement.position(1f, winW, landscapeW))
    }

    @Test
    fun `a window larger than the display is pinned at the origin, never negative`() {
        assertEquals(0, OverlayPlacement.travel(1_200, landscapeH))
        assertEquals(0, OverlayPlacement.clamp(900, 1_200, landscapeH))
        assertEquals(0, OverlayPlacement.position(1f, 1_200, landscapeH))
        assertEquals(0f, OverlayPlacement.fraction(900, 1_200, landscapeH), 0f)
    }

    @Test
    fun `stored fractions outside 0 to 1 cannot escape the display`() {
        assertEquals(0, OverlayPlacement.position(-3f, winH, portraitH))
        assertEquals(portraitH - winH, OverlayPlacement.position(9f, winH, portraitH))
        assertEquals(1f, OverlayPlacement.fraction(9_999, winH, portraitH), 0f)
    }
}
