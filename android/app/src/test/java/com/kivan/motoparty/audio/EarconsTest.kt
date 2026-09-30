package com.kivan.motoparty.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun `tones are synthesised once and keep their length`() {
        for (kind in Earcons.Kind.entries) {
            val expected = kind.notes.sumOf { (_, ms) -> Earcons.RATE * ms / 1000 }
            assertEquals(expected, Earcons.pcm(kind).size)
            // L9: nothing is synthesised or allocated when the beep is due.
            assertSame(Earcons.pcm(kind), Earcons.pcm(kind))
            assertSame(Earcons.pcmFor(kind, call = false), Earcons.pcmFor(kind, call = false))
            assertSame(Earcons.pcm(kind), Earcons.pcmFor(kind, call = true))
        }
    }

    @Test
    fun `the live tone is two faded notes at 35 percent`() {
        val tone = Earcons.pcm(Earcons.Kind.LIVE)
        assertEquals(0.toShort(), tone[0])
        val peak = tone.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("peak $peak", peak in 11_000..11_500)
        // The second note starts from silence too (its own fade-in).
        assertEquals(0.toShort(), tone[Earcons.RATE * 90 / 1000])
        // A rest is silent.
        val error = Earcons.pcm(Earcons.Kind.ERROR)
        val rest = Earcons.RATE * 160 / 1000
        assertTrue((rest until rest + Earcons.RATE * 60 / 1000).all { error[it] == 0.toShort() })
    }

    @Test
    fun `a prepared earcon is taken once, by the play it was made for`() {
        val slot = Earcons.Slot<String, Any>()
        val live = Any()
        assertNull(slot.put("live/call", live))
        // Another earcon (the error tone of a failed open) leaves it where it is.
        assertNull(slot.take("error/media"))
        assertFalse(slot.isEmpty)
        assertSame(live, slot.take("live/call"))
        assertNull(slot.take("live/call"))
        assertTrue(slot.isEmpty)
    }

    @Test
    fun `preparing again hands back the one it replaces`() {
        val slot = Earcons.Slot<String, Any>()
        val first = Any()
        val second = Any()
        slot.put("live/call", first)
        assertSame(first, slot.put("live/media", second))
        assertNull(slot.take("live/call"))
        assertSame(second, slot.take("live/media"))
    }

    @Test
    fun `a stale expiry does not take a newer earcon, a talk's end takes whatever is there`() {
        val slot = Earcons.Slot<String, Any>()
        val old = Any()
        val new = Any()
        slot.put("live/call", old)
        slot.put("live/call", new)
        assertFalse(slot.discard(old))
        assertFalse(slot.isEmpty)
        assertTrue(slot.discard(new))
        assertFalse(slot.discard(new))
        slot.put("live/call", old)
        assertSame(old, slot.discard())
        assertNull(slot.discard())
    }
}
