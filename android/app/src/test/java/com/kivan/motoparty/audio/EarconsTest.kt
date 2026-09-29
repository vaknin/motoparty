package com.kivan.motoparty.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-29: the host-mic talk's LIVE beep (and every CLOSED/OK/ERROR) was inaudible in the
 * AirPods. `dumpsys audio` showed each earcon track `muted … portVolume`: they were
 * `USAGE_ASSISTANCE_SONIFICATION`, i.e. `STREAM_SYSTEM`, which the Pixel had muted.
 */
class EarconsTest {
    @Test
    fun `off the call route an earcon is media, never the system stream`() {
        assertEquals(AudioAttributes.USAGE_MEDIA, Earcons.usage(call = false))
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, Earcons.usage(call = true))
    }

    @Test
    fun `a media earcon starts with silence, the call one does not`() {
        val tone = Earcons.pcm(Earcons.Kind.LIVE)
        val pre = Earcons.RATE * Earcons.PREROLL_MS / 1000
        val media = Earcons.pcmFor(Earcons.Kind.LIVE, call = false)
        assertEquals(pre + tone.size, media.size)
        assertTrue((0 until pre).all { media[it] == 0.toShort() })
        assertTrue(tone.contentEquals(media.copyOfRange(pre, media.size)))
        assertTrue(tone.contentEquals(Earcons.pcmFor(Earcons.Kind.LIVE, call = true)))
    }
}
