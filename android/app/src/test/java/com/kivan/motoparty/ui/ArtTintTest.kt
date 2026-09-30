package com.kivan.motoparty.ui

import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The palette on a real (software) bitmap: what [ArtTints.load] does once Coil has decoded the cover. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtTintTest {
    private val surface = 0xFF0E1013.toInt()

    private fun cover(argb: Int) = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    @Test
    fun aBlueCoverGivesABlueTint() {
        val tint = tintOf(cover(0xFF1D4ED8.toInt()), surface)!!
        assertTrue("%08x".format(tint), (tint and 0xff) > ((tint shr 16) and 0xff))
        assertTrue(luminance(blend(tint, AMBIENT_ALPHA, surface)) <= AMBIENT_MAX_LUMINANCE)
    }

    @Test
    fun aGreyCoverGivesNone() {
        assertNull(tintOf(cover(0xFF808080.toInt()), surface))
    }
}
