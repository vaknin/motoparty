package com.kivan.motoparty.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LiveCue] is the rule for the "live" earcon: it means *your mic is live, talk now*, so it waits
 * for the first captured frame (a), for the route to be really up (b — the framework's SCO
 * communication device, F8) **and**, since F9a, for the headset's own mic signal to be arriving
 * (c — [MicLive]). The times below are the 2026-09-20 AirPods benches': SCO up 1.17–1.38 s after the
 * press, the first captured frame 62–166 ms after that — except in the second cycle, where a frame
 * arrived 0.76 s *before* SCO was up and the old fixed 400 ms delay beeped ~630 ms before the link
 * existed. (a)+(b) were still too early for the AirPods, which begin rendering call audio a varying
 * time after this phone begins sending it (`results/2026-09-20-t3b-talk-f8`: the beep was cut off
 * although the HAL path was applied 0.3–0.5 s earlier), hence (c).
 */
class LiveCueTest {
    private val cue = LiveCue()

    @Test
    fun `the normal order beeps when the mic signal follows the link`() {
        cue.open(1, 0)
        assertNull("the route alone is not enough", cue.route(1, needsSco = true, scoConnected = false, atMs = 300))
        assertNull("the link alone is not enough", cue.scoConnected(1_240))
        assertNull("nor the first frame, which may be the built-in mic", cue.captureUp(1, 1_310))
        val fire = cue.micLive(1, 1_502)
        assertNotNull(fire)
        assertEquals(1, fire!!.session)
        assertEquals(1_310L, fire.captureUpMs)
        assertEquals(1_240L, fire.scoMs)
        assertEquals(1_502L, fire.micMs)
        assertEquals(1_502L, fire.firedMs)
        assertEquals(false, fire.fallback)
    }

    @Test
    fun `a frame delivered before the link waits for the link`() {
        // Bench cycle 2: the mic delivered 0.76 s before SCO was up. Whatever that was, it was not
        // going anywhere, so the beep waits.
        cue.open(2, 0)
        cue.route(2, needsSco = true, scoConnected = false, atMs = 400)
        assertNull(cue.captureUp(2, 1_120))
        assertNull(cue.micLive(2, 1_300))
        val fire = cue.scoConnected(1_880)
        assertNotNull(fire)
        assertEquals(1_120L, fire!!.captureUpMs)
        assertEquals(1_880L, fire.scoMs)
        assertEquals(1_300L, fire.micMs)
        assertEquals(1_880L, fire.firedMs)
    }

    @Test
    fun `the link coming up before the route is known still counts`() {
        cue.open(3, 0)
        assertNull("the link is remembered although the route has not reported", cue.scoConnected(900))
        assertNull(cue.captureUp(3, 1_000))
        assertNull(cue.route(3, needsSco = true, scoConnected = true, atMs = 1_050))
        val fire = cue.micLive(3, 1_210)
        assertNotNull(fire)
        assertEquals(900L, fire!!.scoMs)
        assertEquals(1_210L, fire.firedMs)
    }

    @Test
    fun `all three in any arrival order beep exactly once, at the last of them`() {
        // The mic signal last (the usual AirPods order).
        cue.open(1, 0)
        cue.route(1, needsSco = true, scoConnected = false, atMs = 300)
        assertNull(cue.captureUp(1, 1_300))
        assertNull(cue.scoConnected(1_400))
        assertEquals(1_600L, cue.micLive(1, 1_600)!!.firedMs)

        // The link last (a slow communication-device dispatch after a flap).
        val b = LiveCue()
        b.open(2, 0)
        b.route(2, needsSco = true, scoConnected = false, atMs = 300)
        assertNull(b.captureUp(2, 1_100))
        assertNull(b.micLive(2, 1_500))
        assertEquals(2_400L, b.scoConnected(2_400)!!.firedMs)

        // The first frame last cannot really happen (the detector is fed by the capture loop), but
        // the rule must not depend on the order.
        val c = LiveCue()
        c.open(3, 0)
        c.route(3, needsSco = true, scoConnected = false, atMs = 300)
        assertNull(c.scoConnected(1_200))
        assertNull(c.micLive(3, 1_400))
        val fire = c.captureUp(3, 1_450)
        assertNotNull(fire)
        assertEquals(1_450L, fire!!.firedMs)
        assertNull("and only once", c.micLive(3, 1_600))
        assertNull(c.scoConnected(1_700))
    }

    @Test
    fun `a link that flaps before the beep is not a live mic, and never beeps twice`() {
        // Bench cycle 1: SCO up at 0.97 s, dropped, up for good at 1.88 s.
        cue.open(4, 0)
        cue.route(4, needsSco = true, scoConnected = false, atMs = 300)
        assertNull(cue.scoConnected(970))
        cue.scoDisconnected()
        assertNull("the link is gone again", cue.captureUp(4, 1_100))
        assertNull(cue.micLive(4, 1_200))
        val fire = cue.scoConnected(1_880)
        assertNotNull(fire)
        assertEquals(1_880L, fire!!.scoMs)
        // The mic signal is reported once per engine, so the flap must not have thrown it away.
        assertEquals(1_200L, fire.micMs)
        // A flap *after* the beep changes nothing: one beep per open.
        cue.scoDisconnected()
        assertNull(cue.scoConnected(2_100))
        assertNull(cue.captureUp(4, 2_200))
        assertNull(cue.tick(4, 5_000))
    }

    @Test
    fun `a route that is not Bluetooth needs no link and no mic signal`() {
        cue.open(5, 0)
        assertNull(cue.captureUp(5, 120))
        val fire = cue.route(5, needsSco = false, scoConnected = false, atMs = 160)
        assertNotNull(fire)
        assertEquals(160L, fire!!.firedMs)
        assertNull(fire.scoMs)
        assertNull(fire.micMs)
        assertEquals(false, fire.needsSco)
        assertEquals(false, fire.fallback)
    }

    @Test
    fun `the timer beeps when a signal never comes`() {
        cue.open(6, 0)
        cue.route(6, needsSco = true, scoConnected = false, atMs = 300)
        cue.captureUp(6, 1_000)
        assertNull("not yet", cue.tick(6, 3_499))
        val fire = cue.tick(6, 3_550)
        assertNotNull(fire)
        assertEquals(true, fire!!.fallback)
        assertEquals(1_000L, fire.captureUpMs)
        assertNull("the link was never seen", fire.scoMs)
        assertEquals(3_550L, fire.firedMs)
        assertNull("and only once", cue.tick(6, 4_000))
    }

    @Test
    fun `a headset whose mic signal never arrives beeps on the timer`() {
        // Everything on this phone is ready, the earpieces never deliver: late beep, never silent.
        cue.open(7, 0)
        cue.route(7, needsSco = true, scoConnected = false, atMs = 300)
        cue.captureUp(7, 900)
        cue.scoConnected(1_500)
        assertNull("the mic signal is missing", cue.tick(7, 3_400))
        val fire = cue.tick(7, 3_500)
        assertNotNull(fire)
        assertEquals(true, fire!!.fallback)
        assertEquals(1_500L, fire.scoMs)
        assertNull(fire.micMs)
    }

    @Test
    fun `a talk that closes before it was ready never beeps`() {
        cue.open(7, 0)
        cue.route(7, needsSco = true, scoConnected = false, atMs = 300)
        cue.captureUp(7, 900)
        cue.micLive(7, 1_100)
        cue.close()
        assertNull(cue.scoConnected(1_300))
        assertNull(cue.tick(7, 5_000))
    }

    @Test
    fun `events of an older talk are ignored`() {
        cue.open(8, 0)
        cue.route(8, needsSco = true, scoConnected = false, atMs = 300)
        cue.close()
        cue.open(9, 2_000)
        assertNull("talk 8's frame is not talk 9's", cue.captureUp(8, 2_100))
        assertNull("nor its mic signal", cue.micLive(8, 2_150))
        assertNull("nor is its timer", cue.tick(8, 5_000))
        assertNull(cue.scoConnected(2_200))
        val fire = cue.route(9, needsSco = true, scoConnected = true, atMs = 2_300)
        assertNull("talk 9 has no frame of its own yet", fire)
        assertNull(cue.captureUp(9, 2_400))
        val real = cue.micLive(9, 2_500)
        assertNotNull(real)
        assertEquals(9, real!!.session)
        assertEquals(400L, real.captureUpMs) // offsets are from *this* talk's open
        assertEquals(200L, real.scoMs)
        assertEquals(500L, real.micMs)
    }

    @Test
    fun `a re-open that kept the route beeps once, on the link that is already up`() {
        // TalkAudio collapsed the close+open: the route was never left, the voice engine kept
        // running, so no new SCO connect, no new first frame and no new mic signal will ever arrive.
        // Both of the engine's moments are older than this talk, so they count as +0 ms.
        cue.open(10, 0)
        cue.route(10, needsSco = true, scoConnected = false, atMs = 300)
        cue.scoConnected(1_200)
        cue.captureUp(10, 1_300)
        cue.micLive(10, 1_500)
        cue.close()

        cue.open(11, 4_000)
        assertNull(cue.captureUp(11, 1_300)) // the carried engine's first frame, long before
        assertNull(cue.micLive(11, 1_500)) // and its mic signal, likewise
        val fire = cue.route(11, needsSco = true, scoConnected = true, atMs = 4_040)
        assertNotNull(fire)
        assertEquals(11, fire!!.session)
        assertEquals(0L, fire.captureUpMs)
        assertEquals(0L, fire.scoMs)
        assertEquals(0L, fire.micMs)
        assertEquals(40L, fire.firedMs)
        assertEquals(false, fire.fallback)
    }

    @Test
    fun `the log line is what the bench parses`() {
        cue.open(8, 0)
        cue.route(8, needsSco = true, scoConnected = false, atMs = 300)
        cue.scoConnected(1_240)
        cue.captureUp(8, 1_310)
        val both = cue.micLive(8, 1_502)!!
        assertEquals(
            "live cue: session 8, capture up +1310 ms, sco +1240 ms, mic +1502 ms, fired +1502 ms (both)",
            both.line(),
        )

        val slow = LiveCue()
        slow.open(9, 0)
        slow.route(9, needsSco = true, scoConnected = false, atMs = 300)
        assertEquals(
            "live cue: session 9, capture up none, sco none, mic none, fired +3500 ms (fallback)",
            slow.tick(9, 3_500)!!.line(),
        )

        val wired = LiveCue()
        wired.open(10, 0)
        wired.captureUp(10, 90)
        assertEquals(
            "live cue: session 10, capture up +90 ms, sco n/a, mic n/a, fired +140 ms (both)",
            wired.route(10, needsSco = false, scoConnected = false, atMs = 140)!!.line(),
        )

        val mute = LiveCue()
        mute.open(11, 0)
        assertEquals(
            "live cue: session 11, capture up none, sco unknown, mic unknown, fired +3500 ms (fallback)",
            mute.tick(11, 3_500)!!.line(),
        )
    }
}
