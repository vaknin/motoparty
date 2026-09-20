package com.kivan.motoparty.overlay

import kotlin.math.roundToInt

/**
 * Where the floating buttons sit, in a form that survives a rotation.
 *
 * The overlay window has `FLAG_LAYOUT_NO_LIMITS`, so nothing stops it from being placed outside
 * the display: a `y` that is fine in portrait (2 142 px tall) is far below a landscape screen
 * (1 080 px), and there is then no way to drag it back. So the stored position is a *fraction of
 * the travel* — the free space left over once the window's own size is taken off the display —
 * and every placement is clamped into that travel.
 *
 * A second trap, found on the device: `params.x/y` are *not* display coordinates. The window is
 * laid out inside its parent frame, which excludes the status bar, the display cutout and the
 * navigation bar (Pixel 8, rotation 0: `parent=[0,132][1080,2337]`), so clamping against the raw
 * 1080x2400 bounds let the far corner land 132 px below the screen. Every `displayPx` below is
 * therefore the *usable* extent — [usable], bounds minus those insets — which is exactly the
 * parent frame the coordinates are relative to.
 *
 * Pure arithmetic, no Android: the axes are independent and each is handled on its own.
 */
object OverlayPlacement {
    /**
     * The extent the window is actually laid out in along one axis: the display bounds minus the
     * system-bar / cutout insets at each end. Never negative, never larger than the bounds.
     */
    fun usable(boundsPx: Int, insetStartPx: Int, insetEndPx: Int): Int =
        (boundsPx - insetStartPx.coerceAtLeast(0) - insetEndPx.coerceAtLeast(0)).coerceIn(0, boundsPx)

    /** Free space along one axis: how far the window's origin may move. Never negative. */
    fun travel(windowPx: Int, displayPx: Int): Int = (displayPx - windowPx).coerceAtLeast(0)

    /** Pull a position back inside the usable area, e.g. after a rotation or a drag. */
    fun clamp(positionPx: Int, windowPx: Int, displayPx: Int): Int =
        positionPx.coerceIn(0, travel(windowPx, displayPx))

    /** The fraction to remember for a (clamped) pixel position. 0 = left/top, 1 = right/bottom. */
    fun fraction(positionPx: Int, windowPx: Int, displayPx: Int): Float {
        val travel = travel(windowPx, displayPx)
        if (travel == 0) return 0f
        return (positionPx.toFloat() / travel).coerceIn(0f, 1f)
    }

    /** The pixel position a remembered [fraction] means on the display we have now. */
    fun position(fraction: Float, windowPx: Int, displayPx: Int): Int {
        val travel = travel(windowPx, displayPx)
        return (fraction.coerceIn(0f, 1f) * travel).roundToInt().coerceIn(0, travel)
    }

    // ---- drag to dismiss ----

    /** A rectangle in the same coordinates as `params.x/y`: the window's (inset) parent frame. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Where the X target sits: a [sizePx] square, centred horizontally, [marginPx] above the bottom
     * of the usable area, shrunk to fit a display too small for it. Always inside the usable area,
     * so the same clamp that keeps the buttons on screen keeps the target on screen.
     */
    fun dismissTarget(usableW: Int, usableH: Int, sizePx: Int, marginPx: Int): Box {
        val size = sizePx.coerceIn(0, minOf(usableW, usableH).coerceAtLeast(0))
        val margin = marginPx.coerceIn(0, (usableH - size).coerceAtLeast(0))
        val left = ((usableW - size) / 2).coerceAtLeast(0)
        val bottom = (usableH - margin).coerceAtLeast(size)
        return Box(left, bottom - size, left + size, bottom)
    }

    /**
     * Hit rule: the dragged window's centre x is over the target **and** its bottom edge has
     * reached the target's *vertical centre* — the target grown downwards to the bottom of the
     * screen, because the clamp parks the window's bottom edge at the usable bottom, i.e. below
     * the target's own bottom edge, so a plain centre-in-rect test would leave ~12 px of reachable
     * travel in landscape.
     *
     * F7 (2026-09-20) moved the vertical line from the target's top edge to its centre: with the
     * top edge, 72 % of the vertical travel counted in landscape (20 % in portrait) and a drag
     * released anywhere near the horizontal centre hid the buttons almost regardless of height.
     * The centre halves that — 39 % in landscape, 11 % in portrait, the same 178 px of travel in
     * both — while a drop *on* the X (bottom centre, where the clamp puts it) still hits.
     */
    fun overDismiss(winX: Int, winY: Int, winW: Int, winH: Int, target: Box): Boolean {
        val centreX = winX + winW / 2
        val line = (target.top + target.bottom) / 2
        return centreX >= target.left && centreX <= target.right && winY + winH >= line
    }
}
