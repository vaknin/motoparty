package com.kivan.motoparty.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeScrollTest {
    // A 600 px viewport with 100 px zones, 1000 px/s at the edge.
    private fun speed(y: Float, start: Float = 0f, end: Float = 600f, zone: Float = 100f) =
        edgeScrollSpeed(y, start, end, zone, max = 1000f)

    @Test
    fun stillOutsideTheZones() {
        assertEquals(0f, speed(300f), 0f)
        // The zones' inner borders are still.
        assertEquals(0f, speed(100f), 0f)
        assertEquals(0f, speed(500f), 0f)
    }

    @Test
    fun growsWithTheDepthIntoAZone() {
        assertEquals(-250f, speed(75f), 0.01f)
        assertEquals(-500f, speed(50f), 0.01f)
        assertEquals(-1000f, speed(0f), 0.01f)
        assertEquals(250f, speed(525f), 0.01f)
        assertEquals(500f, speed(550f), 0.01f)
        assertEquals(1000f, speed(600f), 0.01f)
        // Every step deeper is faster.
        (0..100).map { 500f + it }.zipWithNext().forEach { (a, b) -> assertTrue(speed(b) > speed(a)) }
        (0..100).map { 100f - it }.zipWithNext().forEach { (a, b) -> assertTrue(speed(b) < speed(a)) }
    }

    @Test
    fun aFingerPastTheEdgeIsFullSpeed() {
        assertEquals(-1000f, speed(-40f), 0f)
        assertEquals(1000f, speed(900f), 0f)
    }

    @Test
    fun followsTheViewportsOffset() {
        // A viewport that starts at -20 (content padding): the zone is measured from there.
        assertEquals(-500f, speed(30f, start = -20f, end = 580f), 0.01f)
        assertEquals(0f, speed(80f, start = -20f, end = 580f), 0f)
    }

    @Test
    fun aShortViewportKeepsAStillMiddle() {
        // 150 px tall: the zones shrink to 50 px each, so they never meet.
        assertEquals(0f, speed(75f, end = 150f), 0f)
        assertEquals(-1000f, speed(0f, end = 150f), 0.01f)
        assertEquals(500f, speed(125f, end = 150f), 0.01f)
    }

    @Test
    fun nothingWithoutAViewportOrAZone() {
        assertEquals(0f, speed(0f, end = 0f), 0f)
        assertEquals(0f, speed(0f, zone = 0f), 0f)
        assertEquals(0f, speed(Float.NaN), 0f)
        assertEquals(0f, edgeScrollSpeed(0f, 0f, 600f, 100f, max = 0f), 0f)
    }
}
