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
 * Pure arithmetic, no Android: the axes are independent and each is handled on its own.
 */
object OverlayPlacement {
    /** Free space along one axis: how far the window's origin may move. Never negative. */
    fun travel(windowPx: Int, displayPx: Int): Int = (displayPx - windowPx).coerceAtLeast(0)

    /** Pull a position back inside the display, e.g. after a rotation or a drag. */
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
}
