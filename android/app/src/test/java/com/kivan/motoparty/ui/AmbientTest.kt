package com.kivan.motoparty.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientTest {
    private val surface = 0xFF0E1013.toInt()
    private val secondaryText = 0xFFB8BEC7.toInt()
    private val waiting = 0xFFFBBF24.toInt()
    private val warningRed = 0xFFE5484D.toInt()

    private fun backdrop(tint: Int) = blend(tint, AMBIENT_ALPHA, surface)

    @Test
    fun noSwatchesNoTint() {
        assertNull(ambientTint(emptyList(), surface))
        assertNull(ambientTint(listOf(null, null), surface))
    }

    @Test
    fun greyCoverHasNoTint() {
        assertNull(ambientTint(listOf(0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF70757A.toInt()), surface))
    }

    @Test
    fun firstColourWins_greysAndGapsAreSkipped() {
        val blue = 0xFF1D4ED8.toInt()
        val red = 0xFFBE123C.toInt()
        assertEquals(ambientTint(listOf(blue), surface), ambientTint(listOf(null, 0xFF777777.toInt(), blue, red), surface))
        assertEquals(ambientTint(listOf(red), surface), ambientTint(listOf(red, blue), surface))
    }

    @Test
    fun keepsTheHue() {
        val tint = ambientTint(listOf(0xFF1D4ED8.toInt()), surface)!!
        val r = (tint shr 16) and 0xff
        val g = (tint shr 8) and 0xff
        val b = tint and 0xff
        assertTrue("blue stays blue: %08x".format(tint), b > g && g > r)
        assertEquals(0xff, tint ushr 24)
    }

    @Test
    fun nearBlackCoverStillGlows() {
        // A very dark blue: lifted into the band, so the glow is visible at all.
        val tint = ambientTint(listOf(0xFF03081C.toInt()), surface)!!
        assertTrue(luminance(backdrop(tint)) > luminance(surface) * 1.5)
    }

    @Test
    fun anyCoverLeavesTextReadable() {
        // Every hue at full saturation, light and dark, plus the brightest colours there are.
        val covers = buildList {
            for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) {
                add((0xff shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        var tinted = 0
        for (cover in covers) {
            val tint = ambientTint(listOf(cover), surface) ?: continue
            tinted++
            val back = backdrop(tint)
            val name = "%08x -> %08x".format(cover, tint)
            assertTrue(name, luminance(back) <= AMBIENT_MAX_LUMINANCE)
            assertTrue(name, contrast(secondaryText, back) >= 7.0)
            assertTrue(name, contrast(waiting, back) >= 7.0)
            assertTrue(name, contrast(warningRed, back) >= 3.5)
        }
        assertTrue(tinted > 150)
    }

    @Test
    fun brightYellowIsDarkenedBlueIsNot() {
        val yellow = ambientTint(listOf(0xFFFFE600.toInt()), surface)!!
        val blue = ambientTint(listOf(0xFF1D4ED8.toInt()), surface)!!
        // Yellow at the band's lightness would be far too bright; blue fits as it is.
        assertTrue(luminance(yellow) < 0.35)
        assertTrue(luminance(backdrop(yellow)) > AMBIENT_MAX_LUMINANCE * 0.7)
        assertTrue(luminance(backdrop(blue)) <= AMBIENT_MAX_LUMINANCE)
        assertNotNull(blue)
    }

    @Test
    fun contrastIsWcag() {
        assertEquals(21.0, contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.001)
        assertEquals(1.0, contrast(surface, surface), 0.001)
        assertEquals(0xFF808080.toInt(), blend(0xFFFFFFFF.toInt(), 0.5f, 0xFF000000.toInt()))
    }
}
