package com.kivan.motoparty.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LiveCue] is the F7 rule for the "live" earcon: it means *your mic is live, talk now*, so it
 * waits for the first captured frame **and** for the route to be really up (the SCO link, for a
 * Bluetooth headset). The times below are the 2026-09-20 AirPods bench's
 * (`tools/bench/results/2026-09-20-d1-talk-5`): SCO up 1.17–1.38 s after the press, the first
 * captured frame 62–166 ms after that — except in the second cycle, where a frame arrived 0.76 s
 * *before* SCO was up and the old fixed 400 ms delay beeped ~630 ms before the link existed.
 */
class LiveCueTest {
    private val cue = LiveCue()

    @Test
    fun `the normal order beeps when the first frame follows the link`() {
        cue.open(1, 0)
        assertNull("the route alone is not enough", cue.route(1, needsSco = true, scoConnected = false, atMs = 300))
        assertNull("the link alone is not enough", cue.scoConnected(1_240))
        val fire = cue.captureUp(1, 1_310)
        assertNotNull(fire)
        assertEquals(1, fire!!.session)
        assertEquals(1_310L, fire.captureUpMs)
        assertEquals(1_240L, fire.scoMs)
        assertEquals(1_310L, fire.firedMs)
        assertEquals(false, fire.fallback)
    }

    @Test
    fun `a frame delivered before the link waits for the link`() {
        // Bench cycle 2: the mic delivered 0.76 s before SCO was up. Whatever that was, it was not
        // going anywhere, so the beep waits.
        cue.open(2, 0)
        cue.route(2, needsSco = true, scoConnected = false, atMs = 400)
        assertNull(cue.captureUp(2, 1_120))
        val fire = cue.scoConnected(1_880)
        assertNotNull(fire)
        assertEquals(1_120L, fire!!.captureUpMs)
        assertEquals(1_880L, fire.scoMs)
        assertEquals(1_880L, fire.firedMs)
    }

    @Test
    fun `the link coming up before the route is known still counts`() {
        cue.open(3, 0)
        assertNull("the link is remembered although the route has not reported", cue.scoConnected(900))
        assertNull(cue.captureUp(3, 1_000))
        val fire = cue.route(3, needsSco = true, scoConnected = true, atMs = 1_050)
        assertNotNull(fire)
        assertEquals(900L, fire!!.scoMs)
        assertEquals(1_050L, fire.firedMs)
    }

    @Test
    fun `a link that flaps before the beep is not a live mic, and never beeps twice`() {
        // Bench cycle 1: SCO up at 0.97 s, dropped, up for good at 1.88 s.
        cue.open(4, 0)
        cue.route(4, needsSco = true, scoConnected = false, atMs = 300)
        assertNull(cue.scoConnected(970))
        cue.scoDisconnected()
        assertNull("the link is gone again", cue.captureUp(4, 1_100))
        val fire = cue.scoConnected(1_880)
        assertNotNull(fire)
        assertEquals(1_880L, fire!!.scoMs)
        // A flap *after* the beep changes nothing: one beep per open.
        cue.scoDisconnected()
        assertNull(cue.scoConnected(2_100))
        assertNull(cue.captureUp(4, 2_200))
        assertNull(cue.tick(4, 5_000))
    }

    @Test
    fun `a route that is not Bluetooth is up as soon as it reports`() {
        cue.open(5, 0)
        assertNull(cue.captureUp(5, 120))
        val fire = cue.route(5, needsSco = false, scoConnected = false, atMs = 160)
        assertNotNull(fire)
        assertEquals(160L, fire!!.firedMs)
        assertNull(fire.scoMs)
        assertEquals(false, fire.needsSco)
        assertEquals(false, fire.fallback)
    }

    @Test
    fun `the timer beeps when a signal never comes`() {
        cue.open(6, 0)
        cue.route(6, needsSco = true, scoConnected = false, atMs = 300)
        cue.captureUp(6, 1_000)
        assertNull("not yet", cue.tick(6, 2_499))
        val fire = cue.tick(6, 2_550)
        assertNotNull(fire)
        assertEquals(true, fire!!.fallback)
        assertEquals(1_000L, fire.captureUpMs)
        assertNull("the link was never seen", fire.scoMs)
        assertEquals(2_550L, fire.firedMs)
        assertNull("and only once", cue.tick(6, 3_000))
    }

    @Test
    fun `a talk that closes before it was ready never beeps`() {
        cue.open(7, 0)
        cue.route(7, needsSco = true, scoConnected = false, atMs = 300)
        cue.captureUp(7, 900)
        cue.close()
        assertNull(cue.scoConnected(1_300))
        assertNull(cue.tick(7, 3_000))
    }

    @Test
    fun `events of an older talk are ignored`() {
        cue.open(8, 0)
        cue.route(8, needsSco = true, scoConnected = false, atMs = 300)
        cue.close()
        cue.open(9, 2_000)
        assertNull("talk 8's frame is not talk 9's", cue.captureUp(8, 2_100))
        assertNull("nor is its timer", cue.tick(8, 5_000))
        assertNull(cue.scoConnected(2_200))
        val fire = cue.route(9, needsSco = true, scoConnected = true, atMs = 2_300)
        assertNull("talk 9 has no frame of its own yet", fire)
        val real = cue.captureUp(9, 2_400)
        assertNotNull(real)
        assertEquals(9, real!!.session)
        assertEquals(400L, real.captureUpMs) // offsets are from *this* talk's open
        assertEquals(200L, real.scoMs)
    }

    @Test
    fun `a re-open that kept the route beeps once, on the link that is already up`() {
        // TalkAudio collapsed the close+open: the route was never left, the voice engine kept
        // running, so no new SCO connect and no new first frame will ever arrive. The engine's
        // first frame is older than this talk, so it counts as +0 ms.
        cue.open(10, 0)
        cue.route(10, needsSco = true, scoConnected = false, atMs = 300)
        cue.scoConnected(1_200)
        cue.captureUp(10, 1_300)
        cue.close()

        cue.open(11, 4_000)
        assertNull(cue.captureUp(11, 1_300)) // the carried engine's first frame, long before
        val fire = cue.route(11, needsSco = true, scoConnected = true, atMs = 4_040)
        assertNotNull(fire)
        assertEquals(11, fire!!.session)
        assertEquals(0L, fire.captureUpMs)
        assertEquals(0L, fire.scoMs)
        assertEquals(40L, fire.firedMs)
        assertEquals(false, fire.fallback)
    }

    @Test
    fun `the log line is what the bench parses`() {
        cue.open(8, 0)
        cue.route(8, needsSco = true, scoConnected = false, atMs = 300)
        cue.scoConnected(1_240)
        val both = cue.captureUp(8, 1_310)!!
        assertEquals(
            "live cue: session 8, capture up +1310 ms, sco +1240 ms, fired +1310 ms (both)",
            both.line(),
        )

        val slow = LiveCue()
        slow.open(9, 0)
        slow.route(9, needsSco = true, scoConnected = false, atMs = 300)
        assertEquals(
            "live cue: session 9, capture up none, sco none, fired +2500 ms (fallback)",
            slow.tick(9, 2_500)!!.line(),
        )

        val wired = LiveCue()
        wired.open(10, 0)
        wired.captureUp(10, 90)
        assertEquals(
            "live cue: session 10, capture up +90 ms, sco n/a, fired +140 ms (both)",
            wired.route(10, needsSco = false, scoConnected = false, atMs = 140)!!.line(),
        )

        val mute = LiveCue()
        mute.open(11, 0)
        assertEquals(
            "live cue: session 11, capture up none, sco unknown, fired +2500 ms (fallback)",
            mute.tick(11, 2_500)!!.line(),
        )
    }
}
