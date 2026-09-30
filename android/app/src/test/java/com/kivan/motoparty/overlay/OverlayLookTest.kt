package com.kivan.motoparty.overlay

import androidx.compose.ui.graphics.toArgb
import com.kivan.motoparty.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** UA2: the floating button says and shows what the app's TALK button does. */
class OverlayLookTest {
    @Test
    fun `idle is the app's orange TALK, an open talk its red END TALK`() {
        val idle = OverlayLook.of(talkOpen = false, talkLive = false)
        assertEquals("TALK", idle.label)
        assertEquals(Palette.Talk.toArgb(), idle.fill)
        for (live in listOf(false, true)) {
            val open = OverlayLook.of(talkOpen = true, talkLive = live)
            assertEquals("END TALK", open.label)
            assertEquals(Palette.TalkOpen.toArgb(), open.fill)
        }
    }

    @Test
    fun `only a talk that is open and not live yet pulses`() {
        assertEquals(OverlayLook.OPENING, OverlayLook.of(talkOpen = true, talkLive = false))
        assertTrue(OverlayLook.OPENING.pulsing)
        assertEquals(OverlayLook.LIVE, OverlayLook.of(talkOpen = true, talkLive = true))
        assertFalse(OverlayLook.LIVE.pulsing)
        assertFalse(OverlayLook.IDLE.pulsing)
    }

    @Test
    fun `a stale live flag without an open talk is idle`() {
        assertEquals(OverlayLook.IDLE, OverlayLook.of(talkOpen = false, talkLive = true))
    }

    /** `res/values/colors.xml` (launcher icon, notification accent) carries the same brand values. */
    @Test
    fun `the xml colours are the palette's`() {
        val xml = listOf("src/main/res/values/colors.xml", "app/src/main/res/values/colors.xml")
            .map(::File).first { it.exists() }.readText()
        fun color(name: String) =
            Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""").find(xml)!!.groupValues[1].toLong(16).toInt() or 0xFF000000.toInt()
        assertEquals(Palette.Talk.toArgb(), color("brand_talk"))
    }
}
