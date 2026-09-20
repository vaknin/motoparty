package com.kivan.motoparty.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- the parent-frame inset (bench 2026-09-20-d4-overlay-2, Pixel 8 1080x2400) ----
    //
    // params.x/y are relative to the window's parent frame, not the display: rotation 0 showed
    // `parent=[0,132][1080,2337]`. Clamping against the raw bounds put the far corner at
    // `766 1968 1080 2532` — 132 px below a 2400 px display. The four cases below are that bench's
    // four rotations; the insets are the ones the observed frames imply.

    @Test
    fun `the usable extent is the bounds minus the insets at both ends`() {
        assertEquals(2205, OverlayPlacement.usable(2400, 132, 63)) // == parent 2337 - 132
        assertEquals(1080, OverlayPlacement.usable(1080, 0, 0))
    }

    @Test
    fun `absurd insets leave a usable extent inside 0 and the bounds`() {
        assertEquals(0, OverlayPlacement.usable(1080, 900, 900))
        assertEquals(1080, OverlayPlacement.usable(1080, -50, -50))
        assertEquals(0, OverlayPlacement.travel(winW, OverlayPlacement.usable(300, 100, 100)))
    }

    /** The far corner of each bench rotation, as an absolute frame on the display. */
    private fun farCorner(
        boundsW: Int, boundsH: Int,
        left: Int, top: Int, right: Int, bottom: Int,
    ): IntArray {
        val uw = OverlayPlacement.usable(boundsW, left, right)
        val uh = OverlayPlacement.usable(boundsH, top, bottom)
        // A drag to well past the corner, and the fraction it would be stored as and restored from.
        val x = OverlayPlacement.clamp(9_999, winW, uw)
        val y = OverlayPlacement.clamp(9_999, winH, uh)
        val rx = OverlayPlacement.position(OverlayPlacement.fraction(x, winW, uw), winW, uw)
        val ry = OverlayPlacement.position(OverlayPlacement.fraction(y, winH, uh), winH, uh)
        assertEquals("restored x", x, rx)
        assertEquals("restored y", y, ry)
        return intArrayOf(left + x, top + y, left + x + winW, top + y + winH)
    }

    private fun assertOnScreen(boundsW: Int, boundsH: Int, frame: IntArray) {
        val f = frame.joinToString(" ")
        assertTrue("frame $f starts off screen", frame[0] >= 0 && frame[1] >= 0)
        assertTrue("frame $f runs past the right edge ($boundsW)", frame[2] <= boundsW)
        assertTrue("frame $f runs past the bottom edge ($boundsH)", frame[3] <= boundsH)
    }

    @Test
    fun `dragged to the far corner the window stays on screen in rotation 0`() {
        // parent=[0,132][1080,2337]: status bar / cutout 132 on top, navigation bar 63 below.
        val frame = farCorner(1080, 2400, left = 0, top = 132, right = 0, bottom = 63)
        assertOnScreen(1080, 2400, frame)
        assertEquals(766, frame[0]) // unchanged: nothing is inset on the x axis here
        assertEquals(2337, frame[3]) // was 2532, i.e. 132 px below the display
    }

    @Test
    fun `dragged to the far corner the window stays on screen in rotation 90`() {
        // Cutout on the left (132), status bar 74 above: the bench saw 2532 x 1154.
        assertOnScreen(2400, 1080, farCorner(2400, 1080, left = 132, top = 74, right = 0, bottom = 0))
    }

    @Test
    fun `dragged to the far corner the window stays on screen in rotation 180`() {
        // The bench saw the frame end at 2474, 74 px below.
        assertOnScreen(1080, 2400, farCorner(1080, 2400, left = 0, top = 74, right = 0, bottom = 132))
    }

    @Test
    fun `dragged to the far corner the window stays on screen in rotation 270`() {
        // Cutout now on the right: the bench saw the frame end at 2400 x 1154, 74 px below.
        assertOnScreen(2400, 1080, farCorner(2400, 1080, left = 0, top = 74, right = 132, bottom = 0))
    }

    // ---- the drag round trip (bench 2026-09-20-d4-overlay-3) ----
    //
    // A drag never persisted: `place()` runs on every layout pass, and `updateViewLayout` from
    // ACTION_MOVE causes one, so params.x/y were written back from the stored fraction and the
    // window snapped to the old spot. The drag now keeps fx/fy in step with params.x/y, which
    // only works if `place()` is then a no-op: position(fraction(x)) must give back *exactly* x
    // for every clamped x, or `place()` would fight the finger a pixel at a time.

    @Test
    fun `every pixel position round-trips exactly, so place() cannot fight a drag`() {
        // Pixel 8, window 314 x 564: portrait usable 1080 x 2205, landscape usable 2268 x 1017.
        for ((win, display) in listOf(314 to 1080, 564 to 2205, 314 to 2268, 564 to 1017)) {
            val travel = OverlayPlacement.travel(win, display)
            assertTrue("no travel for $win in $display", travel > 0)
            for (x in 0..travel) {
                val back = OverlayPlacement.position(OverlayPlacement.fraction(x, win, display), win, display)
                assertEquals("x $x of $travel (window $win, display $display)", x, back)
            }
        }
    }

    // ---- drag to dismiss (F6, 2026-09-20) ----
    //
    // Real Pixel 8 numbers, density 2.625: usable 1080 x 2205 portrait, 2268 x 1017 landscape;
    // buttons window 314 x 564 px (120 x 216 dp); X target 112 dp = 294 px, 12 dp = 31 px above
    // the usable bottom. The rule is bottom-centre-in-target-grown-downwards, because the clamp
    // parks the window's bottom edge *at* the usable bottom, below the target's own bottom edge.
    // Since F7 the vertical line is the target's *centre*, not its top edge: with the top edge a
    // landscape drag released anywhere near the horizontal centre counted, whatever its height.

    private val dismissSize = 294
    private val dismissMargin = 31
    private fun target(w: Int, h: Int) = OverlayPlacement.dismissTarget(w, h, dismissSize, dismissMargin)
    private fun over(x: Int, y: Int, t: OverlayPlacement.Box) =
        OverlayPlacement.overDismiss(x, y, winW, winH, t)

    @Test
    fun `the X target sits at the bottom centre, inside the usable area`() {
        val t = target(1080, 2205)
        assertEquals(393, t.left)
        assertEquals(687, t.right)
        assertEquals(2174, t.bottom) // 2205 - 31
        assertEquals(1880, t.top)
        assertEquals(dismissSize, t.width)
        assertEquals(dismissSize, t.height)
        assertTrue("target off screen", t.left >= 0 && t.top >= 0 && t.bottom <= 2205 && t.right <= 1080)
    }

    @Test
    fun `dropping the buttons at the bottom centre hits the target in portrait`() {
        val t = target(1080, 2205)
        val travelX = OverlayPlacement.travel(winW, 1080)
        val travelY = OverlayPlacement.travel(winH, 2205)
        assertTrue("bottom centre is a hit", over(travelX / 2, travelY, t))
        assertTrue("a little above the bottom is still a hit", over(travelX / 2, travelY - 120, t))
        assertFalse("half way up is not", over(travelX / 2, travelY / 2, t))
        assertFalse("bottom left corner is not", over(0, travelY, t))
        assertFalse("bottom right corner is not", over(travelX, travelY, t))
        assertFalse("top centre is not", over(travelX / 2, 0, t))
    }

    @Test
    fun `the target is reachable in landscape, where the window is half the screen tall`() {
        // 564 px of window in a 1017 px tall area: the bottom edge can only reach the usable
        // bottom (1017), which is *below* the target's bottom (986) — hence the grown-down rule.
        val t = target(2268, 1017)
        val travelX = OverlayPlacement.travel(winW, 2268)
        val travelY = OverlayPlacement.travel(winH, 1017)
        assertEquals(453, travelY)
        assertTrue("the window cannot reach the target's own bottom edge", travelY + winH > t.bottom)
        assertTrue("bottom centre is a hit", over(travelX / 2, travelY, t))
        assertFalse("bottom left is not", over(0, travelY, t))
        assertFalse("bottom right is not", over(travelX, travelY, t))
        assertFalse("top centre is not", over(travelX / 2, 0, t))
        // F7: half way down the travel used to count here (the old line was the target's top
        // edge, 72 % of the travel); the centre line puts it out of reach.
        assertFalse("half way down is not a drop on the X", over(travelX / 2, travelY / 2, t))
    }

    /**
     * How deliberate the gesture is, vertically (F7). The hit line is the target's centre, which
     * is a fixed distance above the usable bottom, so the same 178 px of travel count in both
     * orientations — 11 % of the portrait travel and 39 % of the (much shorter) landscape one.
     * The old rule (the target's *top* edge) counted 20 % and 72 %.
     */
    @Test
    fun `only the bottom of the vertical travel counts as a drop on the X`() {
        assertEquals(10, hitShareOfTravelPercent(1080, 2205))
        assertEquals(39, hitShareOfTravelPercent(2268, 1017))
    }

    private fun hitShareOfTravelPercent(usableW: Int, usableH: Int): Int {
        val t = target(usableW, usableH)
        val travelX = OverlayPlacement.travel(winW, usableW)
        val travelY = OverlayPlacement.travel(winH, usableH)
        val lowest = (0..travelY).first { over(travelX / 2, it, t) }
        assertEquals("the same distance above the bottom in every orientation", 178, travelY - lowest)
        return (travelY - lowest) * 100 / travelY
    }

    @Test
    fun `the X stays clear of the buttons resting at the top, in both orientations`() {
        assertFalse(over(0, 0, target(1080, 2205)))
        assertFalse(over(OverlayPlacement.travel(winW, 1080), 0, target(1080, 2205)))
        assertFalse(over(0, 0, target(2268, 1017)))
    }

    @Test
    fun `a display too small for the target still gives a box inside it`() {
        val t = target(200, 200)
        assertTrue("$t", t.left >= 0 && t.top >= 0 && t.right <= 200 && t.bottom <= 200)
        assertEquals(200, t.width)
        val flat = target(0, 0)
        assertEquals(0, flat.width)
        assertEquals(0, flat.height)
    }

    @Test
    fun `a fraction saved at the bottom edge in portrait still lands on screen in landscape`() {
        val portraitUsable = OverlayPlacement.usable(2400, 132, 63)
        val landscapeUsable = OverlayPlacement.usable(1080, 74, 0)
        val fy = OverlayPlacement.fraction(9_999, winH, portraitUsable) // an old, saturated 1.0
        val y = OverlayPlacement.position(fy, winH, landscapeUsable)
        assertOnScreen(2400, 1080, intArrayOf(0, 74 + y, winW, 74 + y + winH))
    }
}
