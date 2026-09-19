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
        /** Every command the controller gave, with its virtual time: what "a decision" means. */
        val calls = mutableListOf<String>()

        private var basePos = 0L
        private var baseAt = 0L
        /** Nothing is audible before this instant: the output restart after play()/seekTo(). */
        private var audibleFrom = 0L

        /**
         * Added to the *reported* position only, never to where the audio really is: the A2DP
         * reading that flips between two levels ~200 ms apart (D2 bench, 2026-09-19).
         */
        var readingError: (nowMs: Long) -> Long = { 0L }

        /** Where the audio really is. */
        private val truePositionMs: Long
            get() {
                if (!isPlaying) return basePos
                val elapsed = now() - maxOf(baseAt, audibleFrom)
                return if (elapsed <= 0) basePos else basePos + (elapsed * speed).toLong()
            }

        override val positionMs: Long get() = truePositionMs + readingError(now())

        /** Bank the position reached so far, so a later change of [speed] is not retroactive. */
        private fun freeze() {
            basePos = truePositionMs
            baseAt = now()
        }

        override var speed = 1f
            set(v) {
                calls += "${now()} speed $v"
                if (isPlaying) freeze()
                field = v
            }

        override fun play() {
            calls += "${now()} play"
            if (isPlaying) return
            freeze()
            audibleFrom = now() + outputLagMs
            isPlaying = true
        }

        override fun pause() {
            calls += "${now()} pause"
            freeze()
            isPlaying = false
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            calls += "${now()} seek $positionMs"
            basePos = positionMs.coerceAtLeast(0)
            baseAt = now()
            if (isPlaying) audibleFrom = now() + outputLagMs
        }
    }

    /**
     * Time from `apply()` to the end of the controller's first drift check (its filtered reading),
     * for an anchor [inMs] away. Sampling starts 2 s after play(), which goes out ~300 ms early.
     */
    private fun firstCheckAfter(inMs: Long) = inMs + SyncController.EARLY_CHECK_MS + SyncController.FILTER_SPAN_MS

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

        advanceTimeBy(SyncController.EARLY_CHECK_MS + SyncController.FILTER_SPAN_MS + 100)
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

    /**
     * The resume after talk starts into a route that has just been rebuilt (HFP -> A2DP), so its
     * output lag is nothing like a warm start's. Learning both into one number is what made the
     * estimate ring, so a cold start must teach only the cold estimate.
     */
    @Test
    fun `a cold start uses and learns the cold estimate, leaving the warm one alone`() = runTest {
        val player = FakePlayer(now = { currentTime }, outputLagMs = 900)
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val warmBefore = sync.startLatencyMs
        val coldBefore = sync.coldStartLatencyMs

        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true), cold = true)
        advanceTimeBy(firstCheckAfter(1_000) + 100)

        assertEquals("the warm estimate must not move", warmBefore, sync.startLatencyMs)
        assertTrue(
            "cold estimate ${sync.coldStartLatencyMs} should grow from $coldBefore towards 900",
            sync.coldStartLatencyMs > coldBefore,
        )
    }

    @Test
    fun `a warm start does not move the cold estimate`() = runTest {
        val player = FakePlayer(now = { currentTime }, outputLagMs = 600)
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val coldBefore = sync.coldStartLatencyMs

        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)

        assertTrue("the warm estimate must move", sync.startLatencyMs > SyncController.DEFAULT_START_LATENCY_MS)
        assertEquals("the cold estimate must not", coldBefore, sync.coldStartLatencyMs)
    }

    /** Talk and the recognizer can both be holding: the last release is the one that resumes. */
    @Test
    fun `holds nest`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(5_000)
        assertTrue(player.isPlaying)

        sync.hold()
        sync.hold()
        sync.release()
        advanceTimeBy(3_000)
        assertTrue("one release of two must not resume", !player.isPlaying)

        sync.release()
        advanceTimeBy(3_000)
        assertTrue("the last release resumes", player.isPlaying)

        sync.release() // stray: must not start anything by itself
        advanceTimeBy(1_000)
        assertTrue(player.isPlaying)
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

    // --- the filtered, confirmed drift reading (A2DP position flips, D2 bench 2026-09-19) ---

    private class Logged(val atMs: Long, val line: String)

    private fun TestScope.logged(sync: SyncController): MutableList<Logged> {
        val lines = mutableListOf<Logged>()
        sync.log = { lines += Logged(currentTime, it) }
        return lines
    }

    /** Every speed the controller set other than 1.0, i.e. every nudge. */
    private fun FakePlayer.nudges() = calls.filter { " speed " in it && !it.endsWith("speed 1.0") }

    /** Step until the controller changes speed; returns the instant it did. */
    private fun TestScope.untilNudge(player: FakePlayer, withinMs: Long): Long {
        val until = currentTime + withinMs
        while (player.speed == 1f) {
            assertTrue("no nudge within $withinMs ms: ${player.calls}", currentTime < until)
            advanceTimeBy(50)
        }
        return player.nudges().last().substringBefore(' ').toLong()
    }

    @Test
    fun `a reading that flips by 220 ms for one sample in every second never nudges`() = runTest {
        val player = FakePlayer(now = { currentTime })
        // One sample in four is on the other level, alternately above and below. The slot holds
        // x700 ms, where every single-read check of the old controller fell (it nudged here).
        player.readingError = { t -> if (t % 1_000 in 600L until 850L) (if (t / 1_000 % 2 == 0L) 220L else -220L) else 0L }
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(60_000)

        assertEquals("no nudge: ${player.calls}", emptyList<String>(), player.nudges())
        assertEquals("only the start seek", 1, player.seeks.size)
        assertTrue("drift ${sync.lastDriftMs} ms", abs(sync.lastDriftMs!!) <= SyncController.RESYNC_MS)
    }

    @Test
    fun `a reading that flips by 220 ms for a whole second never nudges`() = runTest {
        val player = FakePlayer(now = { currentTime })
        // 1 s of every 5, covering the first 4 of each check's 9 samples (checks sample from
        // x2700 to x4700), and the instant the old single-read check read.
        player.readingError = { t -> if (t % 5_000 in 2_500L until 3_500L) -220L else 0L }
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(60_000)

        assertEquals("no nudge: ${player.calls}", emptyList<String>(), player.nudges())
        assertEquals("only the start seek", 1, player.seeks.size)
    }

    /** Put the audio (not just its reading) [byMs] ahead of the timeline, with no restart gap. */
    private fun FakePlayer.shove(byMs: Long) {
        val lag = outputLagMs
        outputLagMs = 0
        seekTo(positionMs + byMs)
        outputLagMs = lag
    }

    @Test
    fun `a steady 150 ms offset is nudged once a second reading confirms it`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val log = logged(sync)
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)
        player.shove(150)
        log.clear()

        val nudgeAt = untilNudge(player, withinMs = SyncController.DRIFT_CHECK_MS + SyncController.FILTER_SPAN_MS + 500)
        val waiting = log.filter { "waiting for confirmation" in it.line }
        assertEquals("one unconfirmed reading first: ${log.map { it.line }}", 1, waiting.size)
        assertEquals("check: median err 150 ms (spread 0 ms, n 9), waiting for confirmation", waiting.single().line)
        assertTrue("its line must not look like a check to the bench parser", !Regex("drift -?\\d+ ms").containsMatchIn(waiting.single().line))
        assertEquals("acted on the confirming reading", SyncController.FILTER_SPAN_MS, nudgeAt - waiting.single().atMs)
        assertTrue(log.last().line, log.last().line.startsWith("drift 150 ms: speed 0.96"))
        assertTrue("slow down when ahead", player.speed < 1f)

        advanceTimeBy(4_000 + SyncController.EARLY_CHECK_MS + SyncController.FILTER_SPAN_MS + 100)
        assertEquals("one nudge: ${player.calls}", 1, player.nudges().size)
        assertEquals(1f, player.speed)
        assertTrue("landed at ${sync.lastDriftMs} ms", abs(sync.lastDriftMs!!) <= SyncController.RESYNC_MS)
        // `nudge done:` is read a second after the speed reset.
        val reset = player.calls.last { it.endsWith("speed 1.0") }.substringBefore(' ').toLong()
        val done = log.single { it.line.startsWith("nudge done: drift ") }
        assertEquals(reset + SyncController.NUDGE_DONE_AFTER_MS, done.atMs)
    }

    @Test
    fun `a flip around the end of a nudge does not cause a second, opposite nudge`() = runTest {
        // The D2 case: a -149 ms nudge "landed" at +209. Here: a +150 ms slow-down, and the
        // reading flips 220 ms the other way from 1 s before the nudge ends until well into the
        // first check after it, so that check's median is on the wrong level.
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val log = logged(sync)
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)
        player.shove(150)
        val nudgeAt = untilNudge(player, withinMs = 20_000)
        val end = nudgeAt + 4_000
        player.readingError = { t -> if (t in end - 1_000 until end + 3_100) -220L else 0L }
        log.clear()
        advanceTimeBy(30_000)

        assertEquals("one nudge only: ${player.calls}", 1, player.nudges().size)
        assertTrue("no speed-up: ${player.calls}", player.calls.none { " speed " in it && it.substringAfterLast(' ').toFloat() > 1f })
        val flipped = log.single { "waiting for confirmation" in it.line }.line
        val median = Regex("median err (-?\\d+) ms").find(flipped)!!.groupValues[1].toLong()
        assertTrue("the flip must reach a filtered reading, else this tests nothing: $flipped", median < -SyncController.RESYNC_MS)
        assertTrue("drift ${sync.lastDriftMs} ms", abs(sync.lastDriftMs!!) <= SyncController.RESYNC_MS)
    }

    @Test
    fun `the start lead is learned from the median, not from a flipped sample`() = runTest {
        // Output wakes 600 ms after play(); lead 300 ms, so the start is 300 ms late. The reading
        // flips +500 ms for the first second of the first check: one raw read there would have
        // taught a *shorter* lead (300 - 200 = 100).
        val player = FakePlayer(now = { currentTime }, outputLagMs = 600)
        val firstSample = 1_000 - SyncController.DEFAULT_START_LATENCY_MS + SyncController.EARLY_CHECK_MS
        player.readingError = { t -> if (t in firstSample - 100 until firstSample + 950) 500L else 0L }
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)

        assertEquals(-300L, sync.lastDriftMs)
        assertEquals(300L + (600 - 300) / SyncController.LEARN_DIVISOR, sync.startLatencyMs)
    }

    @Test
    fun `a start more than 1 s off re-seeks on its first filtered reading, without confirmation`() = runTest {
        val player = FakePlayer(now = { currentTime }, outputLagMs = 2_500) // the cold-A2DP outlier
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val log = logged(sync)
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)

        assertEquals("start seek and the re-seek", 2, player.seeks.size)
        assertEquals("drift -2200 ms: seeking (lead 300 ms)", log.last().line)
        assertTrue(log.none { "waiting for confirmation" in it.line })
    }

    // --- the drift trace (logging only) ---

    private class Trace(val atMs: Long, val line: String) {
        val phase: String get() = Regex("phase (\\S+),").find(line)!!.groupValues[1]
    }

    private fun TestScope.traced(sync: SyncController): MutableList<Trace> {
        val lines = mutableListOf<Trace>()
        sync.traceLog = { lines += Trace(currentTime, it) }
        return lines
    }

    @Test
    fun `trace runs once a second for 5 s after a warm and a cold start, in the logged format`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 25 })
        val lines = traced(sync)

        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(20_000)
        // play() goes out `lead` (300 ms) before the anchor: lines at 700, 1700 .. 5700, then none.
        assertEquals((0..5).map { 700L + it * 1_000 }, lines.map { it.atMs })
        assertTrue(lines.all { it.phase == "start-warm" })
        assertTrue("no nudge expected here: ${player.calls}", player.calls.none { it.endsWith("speed 0.95") })
        // The line at play(), 300 ms before the anchor: pos is the seek point (anchor + trim),
        // expected is the timeline now (-300 ms + trim), so err is the lead.
        assertEquals(
            "trace: pos 25 ms, expected -275 ms, err 300 ms, speed 1.0, phase start-warm, t 0",
            lines.first().line,
        )
        assertTrue(lines.none { it.line.contains("drift") })

        lines.clear()
        sync.hold()
        val t0 = currentTime
        sync.release(cold = true)
        advanceTimeBy(20_000)
        assertEquals(6, lines.size)
        assertTrue(lines.all { it.phase == "start-cold" })
        assertEquals(listOf(1_000L), lines.zipWithNext { a, b -> b.atMs - a.atMs }.distinct())
        assertTrue(lines.first().atMs > t0)
    }

    @Test
    fun `trace starts on a nudge and stops 5 s after it ends`() = runTest {
        val player = FakePlayer(now = { currentTime })
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 0 })
        val lines = traced(sync)
        sync.apply(Anchor("t1", 0, currentTime + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 3_100) // past the start's trace window (5.7 s)
        player.seekTo(player.positionMs + 600) // ahead: the next check nudges (slows down)
        lines.clear()
        player.calls.clear()
        advanceTimeBy(30_000)

        val speeds = player.calls.filter { " speed " in it && !it.endsWith("speed 1.0") }
        assertEquals("expected exactly one nudge: ${player.calls}", 1, speeds.size)
        val nudgeAt = speeds.single().substringBefore(' ').toLong()
        val endAt = player.calls.first { it.endsWith("speed 1.0") && it.substringBefore(' ').toLong() > nudgeAt }
            .substringBefore(' ').toLong()

        val nudge = lines.filter { it.phase == "nudge" }
        assertEquals(lines, nudge) // nothing else traced in this window
        assertEquals(nudgeAt, nudge.first().atMs)
        assertEquals(listOf(1_000L), nudge.zipWithNext { a, b -> b.atMs - a.atMs }.distinct())
        val last = nudge.last().atMs
        assertTrue("last line at $last, nudge ended at $endAt", last > endAt + 4_000 && last <= endAt + 5_000)
        // While nudging the line shows the nudge speed, afterwards 1.0.
        assertTrue(nudge.first().line.contains("speed 0.9"))
        assertTrue(nudge.last().line.contains("speed 1.0,"))
    }

    /** Start, idle, nudge, re-seek, hold and cold release: every kind of decision there is. */
    private fun TestScope.decisions(trace: Boolean): Pair<List<String>, Int> {
        val player = FakePlayer(now = { currentTime }, outputLagMs = 450)
        val sync = SyncController(player, backgroundScope, hostNow = { currentTime }, trimMs = { 30 })
        val lines = mutableListOf<String>()
        sync.traceLog = if (trace) { line -> lines += line } else null
        val t0 = currentTime
        sync.apply(Anchor("t1", 0, t0 + 1_000, playing = true))
        advanceTimeBy(firstCheckAfter(1_000) + 100)
        player.seekTo(player.positionMs + 900) // nudge
        advanceTimeBy(25_000)
        player.seekTo(player.positionMs + 3_000) // re-seek
        advanceTimeBy(25_000)
        sync.hold()
        advanceTimeBy(1_500)
        sync.release(cold = true)
        advanceTimeBy(25_000)
        return player.calls.map { "${it.substringBefore(' ').toLong() - t0} ${it.substringAfter(' ')}" } to lines.size
    }

    @Test
    fun `the trace changes no speed, seek, play or pause decision`() = runTest {
        val (withTrace, traced) = decisions(trace = true)
        val (without, untraced) = decisions(trace = false)
        assertTrue("the script must actually trace", traced > 20)
        assertEquals(0, untraced)
        assertTrue("the script must nudge: $withTrace", withTrace.any { " speed " in it && !it.endsWith(" 1.0") })
        assertTrue("the script must re-seek: $withTrace", withTrace.count { " seek " in it } >= 5)
        assertEquals(without, withTrace)
    }
}
