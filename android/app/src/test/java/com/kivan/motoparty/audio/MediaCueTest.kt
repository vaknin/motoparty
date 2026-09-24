package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaCue] is the rule for every sound that plays on the **media** route — the CLOSED and ERROR
 * earcons, the OK tone of a local volume change and the spoken replies. Before F9b they were played
 * the instant `exitCall()` returned, which normally blocks 0.7–1.0 s but was measured at **6 ms**
 * once: that beep went out into the SCO teardown and was heard on the phone speaker *and* the
 * AirPods, cut off.
 *
 * The numbers below are the 2026-09-20 device benches': `exitCall` 5–16 ms on the earpiece
 * (`results/2026-09-20-t3c-earpiece-f9a`, where the LIVE cue also fired with no added delay at all
 * — a non-SCO route must not gain a single millisecond here), and the framework's
 * `onCommunicationDeviceChanged` dispatch landing a few hundred ms after the route really changed
 * (F8, `results/2026-09-20-t3-talk-f7`).
 */
class MediaCueTest {
    private val cue = MediaCue()

    private val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    @Test
    fun `with no call route anywhere the sound plays at once`() {
        // An error earcon with no client connected: nothing is being torn down, nothing to wait for.
        val play = cue.request(1, MediaCue.Kind.ERROR, 5_000)
        assertNotNull("must not be delayed by anything", play)
        assertEquals(0L, play!!.playedMs)
        assertEquals(0L, play.releasedMs)
        assertEquals(false, play.neededDevice)
        assertNull(play.deviceMs)
        assertEquals(false, play.fallback)
    }

    @Test
    fun `a non-SCO route is done the moment exitCall returns`() {
        // The earpiece bench: exitCall takes 5–16 ms and there is no link to tear down, so the
        // closed earcon plays with the release and nothing else. A regression here is audible today.
        cue.routeHeld(0)
        cue.routeReleased(wasSco = false, atMs = 900)
        val play = cue.request(1, MediaCue.Kind.CLOSED, 906)
        assertNotNull("nothing left to wait for", play)
        assertEquals(0L, play!!.playedMs)
        assertEquals(false, play.neededDevice)
    }

    @Test
    fun `a sound asked for while the route is still held waits for the release`() {
        cue.routeHeld(0)
        assertNull("talk is open: the media route is not ours", cue.request(1, MediaCue.Kind.ERROR, 400))
        val plays = cue.routeReleased(wasSco = false, atMs = 1_500)
        assertEquals(1, plays.size)
        assertEquals(1, plays[0].id)
        assertEquals(1_100L, plays[0].playedMs)
        assertEquals(1_100L, plays[0].releasedMs)
        assertEquals(false, plays[0].neededDevice)
        assertEquals(false, plays[0].fallback)
    }

    @Test
    fun `the closed earcon of an SCO talk waits for the framework's next device`() {
        // The bug, in its measured shape: exitCall returned in 6 ms and the earcon was asked for
        // right behind it, while the SCO teardown was still running.
        cue.routeHeld(0)
        assertEquals(emptyList<MediaCue.Play>(), cue.routeReleased(wasSco = true, atMs = 2_000))
        assertNull("exitCall returning is not the route coming back", cue.request(1, MediaCue.Kind.CLOSED, 2_006))
        val plays = cue.device(earpiece, 2_420)
        assertEquals(1, plays.size)
        val p = plays[0]
        assertEquals(0L, p.releasedMs) // released 6 ms before it was asked for
        assertEquals(true, p.neededDevice)
        assertEquals("earpiece", p.deviceType)
        assertEquals(414L, p.deviceMs)
        assertEquals(414L, p.playedMs)
        assertEquals(false, p.fallback)
    }

    @Test
    fun `the released SCO device reporting itself again changes nothing`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 1_000)
        assertNull(cue.request(1, MediaCue.Kind.CLOSED, 1_004))
        assertEquals("still bt_sco: still the link we are waiting to lose", 0, cue.device(sco, 1_100).size)
        assertEquals(1, cue.device(null, 1_300).size)
    }

    @Test
    fun `no device at all is still a device other than bt_sco`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 0)
        assertNull(cue.request(1, MediaCue.Kind.ANNOUNCE, 4))
        val plays = cue.device(null, 300)
        assertEquals(1, plays.size)
        assertEquals("none", plays[0].deviceType)
        assertEquals(296L, plays[0].deviceMs)
    }

    @Test
    fun `a device report with nothing draining plays nothing`() {
        assertEquals(0, cue.device(earpiece, 100).size)
        cue.routeHeld(0)
        assertNull(cue.request(1, MediaCue.Kind.ERROR, 100))
        assertEquals("the route is still held; this is its own link coming up", 0, cue.device(sco, 900).size)
        assertEquals(0, cue.device(earpiece, 950).size)
    }

    @Test
    fun `the fallback plays a sound whose device report never came`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 1_000)
        assertNull(cue.request(1, MediaCue.Kind.CLOSED, 1_006))
        assertEquals("not due yet", 0, cue.tick(2_500).size)
        val plays = cue.tick(3_006)
        assertEquals(1, plays.size)
        assertEquals(true, plays[0].fallback)
        assertEquals(2_000L, plays[0].playedMs)
        assertEquals(true, plays[0].neededDevice)
        assertNull("the framework never said", plays[0].deviceMs)
        assertEquals("fired once and only once", 0, cue.tick(9_000).size)
    }

    @Test
    fun `the fallback also covers a route that is never released`() {
        cue.routeHeld(0)
        assertNull(cue.request(1, MediaCue.Kind.ANNOUNCE, 100))
        val plays = cue.tick(2_100)
        assertEquals(1, plays.size)
        assertEquals(true, plays[0].fallback)
        assertNull("it never got as far as a release", plays[0].releasedMs)
        assertEquals(false, plays[0].neededDevice)
    }

    @Test
    fun `a re-open drops a pending closed earcon and keeps everything else`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 1_000)
        assertNull(cue.request(1, MediaCue.Kind.CLOSED, 1_006))
        assertNull(cue.request(2, MediaCue.Kind.ANNOUNCE, 1_010))
        // The rider pressed talk again before the teardown finished: the closed beep is a lie now.
        assertEquals(listOf(1), cue.routeHeld(1_200))
        assertEquals("the stale beep is gone for good", 0, cue.device(earpiece, 1_400).size)
        // The spoken reply is still true; it waits for the next release.
        val plays = cue.routeReleased(wasSco = false, atMs = 4_000)
        assertEquals(1, plays.size)
        assertEquals(2, plays[0].id)
        assertEquals(2_990L, plays[0].playedMs)
        assertEquals(2_990L, plays[0].releasedMs)
    }

    @Test
    fun `a re-open with nothing pending drops nothing`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 1_000)
        assertEquals(emptyList<Int>(), cue.routeHeld(1_100))
    }

    @Test
    fun `several waiting sounds all play, in the order they were asked for`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 500)
        assertNull(cue.request(7, MediaCue.Kind.CLOSED, 505))
        assertNull(cue.request(8, MediaCue.Kind.ERROR, 600))
        assertNull(cue.request(9, MediaCue.Kind.ANNOUNCE, 700))
        val plays = cue.device(earpiece, 900)
        assertEquals(listOf(7, 8, 9), plays.map { it.id })
        assertEquals(listOf(395L, 300L, 200L), plays.map { it.playedMs })
        assertTrue("each one measures from its own request", plays.all { it.neededDevice })
    }

    @Test
    fun `a later sound is instant again once the route is really back`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 500)
        assertNull(cue.request(1, MediaCue.Kind.CLOSED, 505))
        cue.device(earpiece, 900)
        val play = cue.request(2, MediaCue.Kind.ERROR, 5_000)
        assertNotNull(play)
        assertEquals(0L, play!!.playedMs)
        assertEquals("the earlier teardown is not this sound's business", false, play.neededDevice)
        assertNull(play.deviceMs)
    }

    @Test
    fun `a second talk waits on its own teardown, not the first one's`() {
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 500)
        assertNull(cue.request(1, MediaCue.Kind.CLOSED, 504))
        cue.device(earpiece, 800)
        // Second talk.
        cue.routeHeld(3_000)
        cue.routeReleased(wasSco = true, atMs = 6_000)
        assertNull("the first talk's earpiece report is spent", cue.request(2, MediaCue.Kind.CLOSED, 6_004))
        val plays = cue.device(earpiece, 6_300)
        assertEquals(1, plays.size)
        assertEquals(296L, plays[0].deviceMs)
    }

    @Test
    fun `the log line is what the bench parses`() {
        // Waited for an SCO teardown.
        cue.routeHeld(0)
        cue.routeReleased(wasSco = true, atMs = 2_000)
        cue.request(1, MediaCue.Kind.CLOSED, 2_006)
        assertEquals(
            "media cue: closed, released +0 ms, device earpiece +414 ms, played +414 ms (both)",
            cue.device(earpiece, 2_420)[0].line(),
        )

        // Nothing to wait for.
        val instant = MediaCue()
        assertEquals(
            "media cue: ok, released +0 ms, device n/a, played +0 ms (both)",
            instant.request(2, MediaCue.Kind.OK, 900)!!.line(),
        )

        // Waited for a non-SCO release only.
        val offSco = MediaCue()
        offSco.routeHeld(0)
        offSco.request(3, MediaCue.Kind.ERROR, 100)
        assertEquals(
            "media cue: error, released +800 ms, device n/a, played +800 ms (both)",
            offSco.routeReleased(wasSco = false, atMs = 900)[0].line(),
        )

        // The device report never came.
        val timedOut = MediaCue()
        timedOut.routeHeld(0)
        timedOut.routeReleased(wasSco = true, atMs = 100)
        timedOut.request(4, MediaCue.Kind.ANNOUNCE, 104)
        assertEquals(
            "media cue: announce, released +0 ms, device none, played +2000 ms (fallback)",
            timedOut.tick(2_104)[0].line(),
        )
    }

    @Test
    fun `the timeout is a knob`() {
        val quick = MediaCue(timeoutMs = 300)
        quick.routeHeld(0)
        quick.routeReleased(wasSco = true, atMs = 0)
        assertNull(quick.request(1, MediaCue.Kind.CLOSED, 0))
        assertEquals(0, quick.tick(299).size)
        assertEquals(1, quick.tick(300).size)
    }
}
