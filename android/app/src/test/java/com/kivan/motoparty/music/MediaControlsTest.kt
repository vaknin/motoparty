package com.kivan.motoparty.music

import com.kivan.motoparty.LinkService
import com.kivan.motoparty.music.MediaControls.Button
import org.junit.Assert.assertEquals
import org.junit.Test

/** Improvement #6: what the system's media controls (shade, lock screen) offer next to the transport. */
class MediaControlsTest {
    @Test
    fun `the talk button is worded like the notification action`() {
        assertEquals("Talk", Button.TALK.label)
        assertEquals("End talk", Button.END_TALK.label)
        assertEquals(Button.TALK, MediaControls.buttonsFor(talkOpen = false, overlayHidden = false).first())
        assertEquals(Button.END_TALK, MediaControls.buttonsFor(talkOpen = true, overlayHidden = false).first())
    }

    @Test
    fun `two buttons, Stop unless the floating button has to be brought back`() {
        assertEquals(listOf(Button.TALK, Button.STOP), MediaControls.buttonsFor(talkOpen = false, overlayHidden = false))
        assertEquals(listOf(Button.TALK, Button.SHOW_BUTTONS), MediaControls.buttonsFor(talkOpen = false, overlayHidden = true))
        assertEquals(listOf(Button.END_TALK, Button.SHOW_BUTTONS), MediaControls.buttonsFor(talkOpen = true, overlayHidden = true))
    }

    @Test
    fun `every button is its own session command`() {
        assertEquals(Button.entries.size, Button.entries.map { it.action }.toSet().size)
    }

    @Test
    fun `the session is told only when the buttons change`() {
        val seen = mutableListOf<List<Button>>()
        MediaControls.onButtonsChanged = { seen += it }
        try {
            MediaControls.show(talkOpen = false, overlayHidden = false)
            val before = seen.size
            MediaControls.show(talkOpen = true, overlayHidden = false)
            MediaControls.show(talkOpen = true, overlayHidden = false)
            MediaControls.show(talkOpen = false, overlayHidden = false)
            assertEquals(
                listOf(listOf(Button.END_TALK, Button.STOP), listOf(Button.TALK, Button.STOP)),
                seen.drop(before),
            )
            assertEquals(listOf(Button.TALK, Button.STOP), MediaControls.buttons)
        } finally {
            MediaControls.onButtonsChanged = null
        }
    }

    @Test
    fun `the notification is a media one only with a track and a working microphone`() {
        fun kind(micOff: Boolean, trackLoaded: Boolean) = LinkService.NotificationKind.of(micOff, trackLoaded)
        assertEquals(LinkService.NotificationKind.MEDIA, kind(false, true))
        assertEquals(LinkService.NotificationKind.PLAIN, kind(false, false))
        // "Microphone off – tap to restore" must stay readable: the media controls would hide it.
        assertEquals(LinkService.NotificationKind.PLAIN, kind(true, true))
        assertEquals(LinkService.NotificationKind.PLAIN, kind(true, false))
    }

    @Test
    fun `the channel is called Ride status`() {
        assertEquals("Ride status", LinkService.CHANNEL_NAME)
    }
}
