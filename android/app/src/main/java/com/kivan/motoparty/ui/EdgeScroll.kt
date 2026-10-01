package com.kivan.motoparty.ui

/**
 * How fast a list scrolls by itself while a dragged row's finger is at [y], in a viewport that runs
 * from [start] to [end] (all px): 0 outside the two edge zones, then growing linearly with how deep
 * the finger is into a zone, up to [max] at the very edge and beyond it. Negative = toward the top
 * of the list, positive = toward its end; the unit is [max]'s (px per second in [QueueTab]).
 *
 * A zone is [zone] deep, but never more than a third of the viewport, so on a short list (landscape)
 * the two zones cannot meet and there is always a still band in the middle.
 */
fun edgeScrollSpeed(y: Float, start: Float, end: Float, zone: Float, max: Float): Float {
    val size = end - start
    if (size <= 0f || zone <= 0f || max <= 0f || y.isNaN()) return 0f
    val z = minOf(zone, size / 3f)
    val intoTop = (start + z - y) / z
    val intoBottom = (y - (end - z)) / z
    return when {
        intoTop > 0f -> -max * intoTop.coerceAtMost(1f)
        intoBottom > 0f -> max * intoBottom.coerceAtMost(1f)
        else -> 0f
    }
}
