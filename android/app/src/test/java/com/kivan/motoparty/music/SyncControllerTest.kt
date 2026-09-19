package com.kivan.motoparty.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The drift rule of PROTOCOL.md "Music flow" step 4: start early by the measured output delay,
 * correct 80 ms..1 s with a rate change of at most +-5 %, re-seek only above 1 s.
 *
 * The controller's drift check is an endless loop, so it runs in [TestScope.backgroundScope] and
 * the tests step virtual time explicitly (never `advanceUntilIdle`, which would never return).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerTest {

    /**
     * A player whose position follows virtual time at [speed] once playing. [outputLagMs] stands
     * in for the A2DP restart lag: nothing is audible (the position does not move) until it has
     * passed, which is exactly what `startLatencyMs` exists to compensate.
     */
    private class FakePlayer(
        val now: () -> Long,
        var outputLagMs: Long = SyncController.DEFAULT_START_LATENCY_MS,
    ) : PlayerControls {
        override var loadedId: String? = "t1"
        override var isReady = true
        override var isPlaying = false
            private set
        val seeks = mutableListOf<Long>()

        private var basePos = 0L
        private var baseAt = 0L
        /** Nothing is audible before this instant: the output restart after play()/seekTo(). */
        private var audibleFrom = 0L

        override val positionMs: Long
            get() {
                if (!isPlaying) return basePos
                val elapsed = now() - maxOf(baseAt, audibleFrom)
                return if (elapsed <= 0) basePos else basePos + (elapsed * speed).toLong()
            }

        /** Bank the position reached so far, so a later change of [speed] is not retroactive. */
        private fun freeze() {
            basePos = positionMs
            baseAt = now()
        }

        override var speed = 1f
            set(v) {
                if (isPlaying) freeze()
                field = v
            }

        override fun play() {
            if (isPlaying) return
            freeze()
            audibleFrom = now() + outputLagMs
            isPlaying = true
        }

        override fun pause() {
            freeze()
            isPlaying = false
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            basePos = positionMs.coerceAtLeast(0)
            baseAt = now()
            if (isPlaying) audibleFrom = now() + outputLagMs
        }
    }

    /** Time from `apply()` to the controller's first drift check, for an anchor [inMs] away. */
    private fun firstCheckAfter(inMs: Long) = inMs + SyncController.EARLY_CHECK_MS

    @Test
    fun `does not start before the anchor time, then runs on the timeline`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val anchor = Anchor("t1", positionMs = 0, atHostTimeMs = currentTime + 2_000, playing = true)

        sync.apply(anchor)
        advanceTimeBy(1_000)
        assertTrue("must not play a second before the anchor", !player.isPlaying)

        // play() goes out early by the start latency, so it has fired by the anchor time itself.
        advanceTimeBy(1_100)
        assertTrue("must be playing by the anchor time", player.isPlaying)

        advanceTimeBy(firstCheckAfter(2_000))
        val drift = player.positionMs - anchor.expectedAt(currentTime)
        assertTrue("drift $drift ms should be small", abs(drift) < SyncController.RESYNC_MS)
    }

    @Test
    fun `small drift is left alone`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(25_000) // start plus two full 10 s check cycles

        assertEquals("no rate nudge", 1f, player.speed)
        assertEquals("only the start seek", 1, player.seeks.size)
        val drift = sync.lastDriftMs
        assertNotNull("a check must have run", drift)
        assertTrue("drift $drift ms", abs(drift!!) <= SyncController.RESYNC_MS)
    }

    @Test
    fun `mid-range drift is corrected by a rate nudge, not a seek`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)

        // Shove the player 400 ms ahead of the shared timeline.
        player.seekTo(player.positionMs + 400)
        val seeksAfterShove = player.seeks.size

        var sawNudge = false
        var extreme = 1f
        repeat(400) {
            advanceTimeBy(100)
            if (player.speed != 1f) {
                sawNudge = true
                if (abs(player.speed - 1f) > abs(extreme - 1f)) extreme = player.speed
            }
        }
        assertTrue("expected a rate nudge", sawNudge)
        assertTrue("nudge $extreme must stay within +-5 %", abs(extreme - 1f) <= 0.05f + 1e-6f)
        assertTrue("must slow down when ahead, got $extreme", extreme < 1f)
        assertEquals("must not re-seek below 1 s of drift", seeksAfterShove, player.seeks.size)
        assertEquals("speed must return to 1", 1f, player.speed)

        val drift = sync.lastDriftMs!!
        assertTrue("drift $drift ms should be back under the threshold", abs(drift) <= SyncController.RESYNC_MS)
    }

    @Test
    fun `drift above one second re-seeks`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)

        player.seekTo(player.positionMs + 3_000) // well above the 1 s seek threshold
        val seeksAfterShove = player.seeks.size

        advanceTimeBy(SyncController.DRIFT_CHECK_MS + 100)
        assertTrue("expected a corrective seek", player.seeks.size > seeksAfterShove)
        assertEquals("a seek, not a nudge", 1f, player.speed)

        advanceTimeBy(SyncController.EARLY_CHECK_MS + 100)
        val drift = sync.lastDriftMs!!
        assertTrue("drift $drift ms after the re-seek", abs(drift) <= SyncController.RESYNC_MS)
    }

    @Test
    fun `start latency is learned from the first check`() = runTest {
        // A headset whose output wakes 600 ms after play(): the start is late, so the learned
        // latency must grow, making the next start earlier.
        val player = FakePlayer(now = { currentTime }, outputLagMs = 600)
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val before = sync.startLatencyMs

        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)
        assertTrue(
            "learned latency ${sync.startLatencyMs} should grow from $before towards 600",
            sync.startLatencyMs > before,
        )
        assertTrue("learned latency must stay within its cap", sync.startLatencyMs <= 1_000)
    }

    @Test
    fun `repeated starts converge on the real output lag without overshooting past it`() = runTest {
        // Regression for the ringing seen on the bench (-693 -> +428 -> +173 -> +93 ms with a
        // weight-1/2 average). The estimate must approach 600 from below and never pass it.
        val player = FakePlayer(now = { currentTime }, outputLagMs = 600)
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })

        var previous = sync.startLatencyMs
        repeat(6) {
            sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
            advanceTimeBy(firstCheckAfter(1_000) + 100)
            val now = sync.startLatencyMs
            assertTrue("estimate $now overshot the true 600 ms lag", now <= 600)
            assertTrue("estimate went backwards: $previous -> $now", now >= previous)
            previous = now
            sync.apply(null)
            advanceTimeBy(500)
        }
        assertTrue("estimate $previous should have closed most of the 300..600 gap", previous > 450)
    }

    @Test
    fun `a cold-start outlier is not learned from`() = runTest {
        // The cold-A2DP case seen on the bench: the stream takes ~2.5 s to wake, so the first
        // check is far past the seek threshold. That measurement must not poison the average.
        val player = FakePlayer(now = { currentTime }, outputLagMs = 2_500)
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val before = sync.startLatencyMs

        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)
        assertEquals("outlier must be ignored", before, sync.startLatencyMs)
    }

    @Test
    fun `hold stops output without losing the timeline, release rejoins it`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val anchor = Anchor("t1", 0, currentTime + 1_000, playing = true)
        sync.apply(anchor)
        advanceTimeBy(5_000)
        assertTrue(player.isPlaying)

        sync.hold()
        assertTrue("hold must stop output", !player.isPlaying)
        assertEquals("hold must not change the anchor", anchor, sync.anchor)
        advanceTimeBy(4_000)
        assertTrue("must stay stopped while held", !player.isPlaying)

        sync.release()
        advanceTimeBy(3_000)
        assertTrue("release must resume", player.isPlaying)
        val drift = player.positionMs - anchor.expectedAt(currentTime)
        assertTrue("rejoined $drift ms off the timeline", abs(drift) < SyncController.RESYNC_MS)
    }

    @Test
    fun `a paused anchor seeks and stays paused`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", positionMs = 12_345, atHostTimeMs = currentTime, playing = false))
        advanceTimeBy(5_000)
        assertTrue(!player.isPlaying)
        assertEquals(12_345L, player.seeks.last())
        assertEquals(12_345L, player.positionMs)
    }

    @Test
    fun `a null anchor pauses`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(5_000)
        assertTrue(player.isPlaying)

        sync.apply(null)
        advanceTimeBy(5_000)
        assertTrue("a null anchor must stop playback", !player.isPlaying)
        assertEquals("and drop the anchor", null, sync.anchor)
    }

    @Test
    fun `the latency trim shifts the local position ahead`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val trim = 250
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { trim })
        val anchor = Anchor("t1", 0, currentTime + 1_000, playing = true)
        sync.apply(anchor)
        advanceTimeBy(firstCheckAfter(1_000) + 100)

        val ahead = player.positionMs - anchor.expectedAt(currentTime)
        assertTrue("trim should put us ~$trim ms ahead, got $ahead", abs(ahead - trim) < SyncController.RESYNC_MS)
        assertEquals("the trim is not drift: no nudge", 1f, player.speed)
    }
}
