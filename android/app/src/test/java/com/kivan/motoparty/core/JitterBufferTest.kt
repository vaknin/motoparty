package com.kivan.motoparty.core

import com.kivan.motoparty.core.JitterBuffer.Out
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private var now = 0L
    private val jb = JitterBuffer { now }

    private fun put(seq: Int, frame: Int, tag: Int = frame) =
        jb.insert(seq and 0xffff, (frame.toLong() * 320) and 0xffffffffL, byteArrayOf(tag.toByte()))

    /** One 20 ms playback tick. */
    private fun pull(): Out = jb.pull().also { now += 20 }

    private fun Out.tag(): Int = when (this) {
        is Out.Play -> payload[0].toInt()
        is Out.Fec -> -100 - successor[0].toInt()
        Out.Conceal -> -1
        Out.Silence -> -2
    }

    private fun pulls(n: Int) = List(n) { pull().tag() }

    /** Every case of the shared vectors; the Swift and Python buffers run the same file. */
    @Test
    fun fixtureCases() {
        val cases = Fixtures.load("jitter.json").jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 12)
        for (c in cases) {
            val name = c.jsonObject["name"]!!.jsonPrimitive.content
            var at = 0L
            val buf = JitterBuffer { at }
            for ((i, step) in c.jsonObject["steps"]!!.jsonArray.withIndex()) {
                val o = step.jsonObject
                val t = o["at"]!!.jsonPrimitive.long
                assertTrue("$name step $i: at goes back", t >= at)
                at = t
                val where = "$name step $i at $at"
                o["insert"]?.jsonArray?.let {
                    val seq = it[0].jsonPrimitive.int
                    buf.insert(seq, it[1].jsonPrimitive.long, byteArrayOf((seq shr 8).toByte(), seq.toByte()))
                }
                o["keepalive"]?.let { buf.insertKeepalive(it.jsonPrimitive.int) }
                o["pull"]?.let {
                    fun seqOf(b: ByteArray) = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                    val got = when (val out = buf.pull()) {
                        is Out.Play -> "play ${seqOf(out.payload)}"
                        is Out.Fec -> "fec ${seqOf(out.successor)}"
                        Out.Conceal -> "conceal"
                        Out.Silence -> "silence"
                    }
                    assertEquals(where, it.jsonPrimitive.content, got)
                }
                o["expect"]?.jsonObject?.let { e ->
                    e["targetMs"]?.let { assertEquals("$where targetMs", it.jsonPrimitive.int, buf.targetMs) }
                    e["underruns"]?.let { assertEquals("$where underruns", it.jsonPrimitive.int, buf.underruns) }
                    e["shed"]?.let { assertEquals("$where shed", it.jsonPrimitive.int, buf.shed) }
                }
            }
        }
    }

    @Test
    fun firstFramePlaysTargetAfterArrival() {
        put(0, 0); put(1, 1)
        assertEquals(listOf(-2, -2, 0, 1), pulls(4))
    }

    @Test
    fun reorderedPacketsPlayInTsOrder() {
        put(1, 1); put(0, 0); put(2, 2)
        assertEquals(listOf(-2, -2, 0), pulls(3))
        put(4, 4); put(3, 3)
        assertEquals(listOf(1, 2, 3, 4), pulls(4))
        assertEquals(0, jb.shed)
    }

    @Test
    fun lostFrameWithSuccessorUsesFec() {
        put(0, 0); put(1, 1)
        assertEquals(listOf(-2, -2, 0), pulls(3))
        put(3, 3)
        assertEquals(listOf(1, -103, 3), pulls(3))
    }

    @Test
    fun lostFramesWithoutImmediateSuccessorConceal() {
        put(0, 0); put(1, 1)
        assertEquals(listOf(-2, -2, 0), pulls(3))
        put(4, 4)
        // Frames 2 and 3 lost: 2 has no immediate successor (PLC), 3 has one (FEC).
        assertEquals(listOf(1, -1, -104, 4), pulls(4))
    }

    @Test
    fun silenceGapStartsANewSpurt() {
        // seq contiguous, ts jumps 10 frames: the sender stopped sending (DTX), not loss.
        put(0, 0); put(1, 1)
        assertEquals(listOf(-2, -2, 0, 1), pulls(4))
        put(2, 20) // arrives now: plays 40 ms (target) later, never via FEC/PLC
        assertEquals(listOf(-2, -2, 20), pulls(3))
        assertEquals(0, jb.underruns)
    }

    @Test
    fun keepaliveInsideSilenceGapIsNotLoss() {
        put(0, 0); put(1, 1)
        pulls(4)
        jb.insertKeepalive(2) // shares the seq counter
        put(3, 30)
        assertEquals(listOf(-2, -2, 30), pulls(3))
    }

    @Test
    fun emptyBufferConcealsBrieflyThenFallsSilentWithoutUnderrun() {
        put(0, 0); put(1, 1)
        pulls(4)
        assertEquals(listOf(-1, -1, -1, -2, -2), pulls(5))
        assertEquals(0, jb.underruns)
    }

    @Test
    fun latePacketIsAnUnderrunAppliedAtNextSpurt() {
        put(0, 0); put(1, 1)
        pulls(5) // plays 0, 1, conceals slot 2
        put(2, 2) // after its slot
        assertEquals(1, jb.underruns)
        assertEquals(60, jb.targetMs)
        // The running spurt keeps its timing: frame 3 plays in its own slot.
        put(3, 3)
        assertEquals(3, pull().tag())
        // Next spurt (silence gap) waits the new 60 ms target.
        put(4, 40)
        assertEquals(listOf(-2, -2, -2, 40), pulls(4))
    }

    @Test
    fun targetRaisesOncePerSpurtAndCapsAt200() {
        var seq = 0
        var frame = 0
        repeat(12) { spurt ->
            put(seq++, frame, tag = 1); put(seq++, frame + 1, tag = 2)
            while (pull().tag() != 2) Unit
            repeat(3) { put(0, frame) } // three late packets in this spurt
            frame += 50 // silence gap -> next spurt
            assertEquals("after spurt $spurt", minOf(200, 40 + 20 * (spurt + 1)), jb.targetMs)
        }
        assertEquals(36, jb.underruns)
    }

    @Test
    fun targetLowersAfterTenSecondsWithoutUnderrun() {
        put(0, 0); put(1, 1)
        pulls(5)
        put(2, 2) // late -> 60
        assertEquals(60, jb.targetMs)
        var seq = 3
        var frame = 3
        repeat(510) { put(seq++, frame++); pull() } // 10 s of steady audio
        assertEquals(40, jb.targetMs)
        repeat(510) { put(seq++, frame++); pull() }
        assertEquals(40, jb.targetMs)
    }

    @Test
    fun seqAndTsWrapAround() {
        val startFrame = (0xffffffffL / 320).toInt() - 1
        var seq = 0xfffe
        var frame = startFrame
        val tags = List(8) { i -> put(seq++, frame++, tag = i); pull().tag() }
        assertEquals(listOf(-2, -2, 0, 1, 2, 3, 4, 5), tags)
    }

    @Test
    fun senderRestartResyncs() {
        put(0, 0); put(1, 1)
        pulls(4)
        put(0, 1_000_000, tag = 7)
        put(1, 1_000_001, tag = 8)
        assertEquals(listOf(-2, -2, 7, 8), pulls(4))
    }

    @Test
    fun unwrapPicksNearest() {
        assertEquals(65536L, JitterBuffer.unwrap(0, 65535, 65536))
        assertEquals(65535L, JitterBuffer.unwrap(65535, 65536, 65536))
        assertEquals(5L, JitterBuffer.unwrap(5, 3, 65536))
    }

    // --- per-talk counters (the `talk stats` line) ---

    @Test
    fun perTalkCountersOverAScriptedTalk() {
        // seq wraps 65535 -> 0 mid-talk; frame 2 (seq 65535) is lost.
        put(65533, 0); put(65534, 1)
        assertEquals(listOf(-2, -2, 0), pulls(3))
        put(0, 3)
        assertEquals(listOf(1, -103, 3), pulls(3)) // FEC for the lost frame
        assertEquals(-1, pull().tag()) // frame 4 is not here at its slot: PLC
        put(1, 4) // ... and arrives after it: late
        assertEquals(listOf(-1, -1, -2), pulls(3)) // brief PLC, then silence
        jb.insertKeepalive(2) // the sender went idle (DTX)
        put(3, 40)
        // A new spurt, at the target the late packet raised (60 ms): the late packet is the last
        // audio seq before the gap and must not make the gap look like loss.
        assertEquals(listOf(-2, -2, -2, 40), pulls(4))

        assertEquals(5, jb.received) // 0, 1, 3, the late 4, and 40
        assertEquals(1, jb.keepalives)
        assertEquals(1, jb.fecUsed)
        assertEquals(3, jb.concealed)
        assertEquals(7L, jb.seqSpan) // 65533 .. 65539 unwrapped
        assertEquals(1, jb.underruns)
        assertEquals(1L, statsOf(jb).lost) // only seq 65535
    }

    @Test
    fun seqSpanStartsAtTheLowestSeqNotTheFirstArrival() {
        put(11, 1); put(10, 0); put(13, 3) // first two swapped, 12 lost
        assertEquals(4L, jb.seqSpan)
        assertEquals(1L, statsOf(jb).lost)
    }

    @Test
    fun seqSpanCoversAKeepaliveBelowTheFirstAudioAcrossTheWrap() {
        put(0, 0)
        jb.insertKeepalive(0xffff) // just before it, across the wrap
        assertEquals(2L, jb.seqSpan)
        assertEquals(0L, statsOf(jb).lost)
    }

    @Test
    fun resetZeroesTheTalkCountersButNotUnderruns() {
        put(0, 0); put(1, 1); put(3, 3)
        pulls(5)
        put(2, 2) // late
        jb.insertKeepalive(4)
        jb.reset()
        assertEquals(listOf(0, 0, 0, 0), listOf(jb.received, jb.keepalives, jb.fecUsed, jb.concealed))
        assertEquals(0L, jb.seqSpan)
        assertEquals(1, jb.underruns) // the UI's running total; VoiceEngine subtracts the start value
        put(500, 0)
        assertEquals(1L, jb.seqSpan)
    }

    private fun statsOf(jb: JitterBuffer) = TalkStats(
        captured = 0, sent = 0, received = jb.received, played = 0, seqSpan = jb.seqSpan,
        late = jb.underruns, fec = jb.fecUsed, plc = jb.concealed, keepalives = jb.keepalives,
        jitterTargetMs = jb.targetMs,
    )

    // ---- D1 run 1: "rx 958 received, 8 played, 950 late" ----
    // The play-out clock got ahead of a steady stream by less than the 3 s resync and never
    // came back: both then advance 50 frames/s, so every later packet was dropped as late.

    /** One packet arrives on time and the output takes one frame; [n] times. Returns the plays. */
    private var seqN = 0
    private var frameN = 0
    private fun stream(n: Int): Int = (0 until n).count {
        put(seqN++, frameN++, tag = 1)
        pull() is Out.Play
    }

    @Test
    fun outputBurstAfterRouteSwitchDoesNotLeaveEveryLaterPacketLate() {
        assertEquals(8, stream(10)) // 2 silent pulls, then playing
        // The re-routed AudioTrack accepts 10 frames at once (no time passes between writes).
        repeat(10) { jb.pull() }
        val played = stream(100)
        assertTrue("played $played of 100", played >= 90)
        // One late packet; the second is two frames past the last played one with only that late
        // seq between, so it starts a new spurt at once, before the 100 ms re-anchor is needed.
        assertEquals(1, jb.underruns)
        assertEquals(0, jb.reanchors)
    }

    @Test
    fun outputBurstWithALostPacketIsReanchored() {
        assertEquals(8, stream(10))
        repeat(10) { jb.pull() }
        seqN++; frameN++ // lost: a real seq gap, so the late packets cannot be a new spurt
        val played = stream(100)
        assertTrue("played $played of 100", played >= 90)
        assertTrue("late ${jb.underruns}", jb.underruns <= 6)
        assertEquals(1, jb.reanchors)
    }

    @Test
    fun senderThatLostFramesWithoutATsJumpStartsANewSpurt() {
        assertEquals(18, stream(20))
        repeat(15) { pull() } // 300 ms without packets; the sender's ts does not jump after it
        val played = stream(100)
        assertTrue("played $played of 100", played >= 90)
        assertEquals(1, jb.underruns)
        assertEquals(0, jb.reanchors)
    }

    @Test
    fun aLateBurstAfterANetworkStallAddsNoLatency() {
        stream(20)
        repeat(10) { pull() } // 200 ms with nothing arriving...
        // ...then all of it at once: the first is late, the second starts a new spurt (at the
        // raised 60 ms) and the rest queue behind it.
        repeat(10) { put(seqN++, frameN++, tag = 2) }
        assertEquals(1, jb.underruns)
        // The spurt start keeps only the newest target's worth: no extra latency from the burst.
        assertEquals(97, stream(100))
        assertEquals(9, jb.shed)
        assertEquals(80, jb.maxDepthMs) // 4 queued when the new spurt's first frame is due
        assertEquals(0, jb.reanchors)
    }

    // ---- shedding a backlog (PROTOCOL.md "Shedding a backlog"; the cases are in jitter.json) ----

    @Test
    fun aPlayoutStallIsShedWithinAboutASecond() {
        stream(100)
        repeat(35) { put(seqN++, frameN++, tag = 1); now += 20 } // 700 ms without a pull
        stream(60)
        assertEquals(36, jb.shed) // hard cap at once, the rest at the end of the 1 s window
        val before = jb.shed
        assertEquals(200, stream(200))
        assertEquals(before, jb.shed)
        assertEquals(0, jb.underruns)
        assertEquals(40, jb.targetMs)
    }

    @Test
    fun depthStatsAndShedAreZeroedByReset() {
        stream(100)
        assertEquals(60, jb.maxDepthMs) // 3 queued each time a frame is due in this script
        assertEquals(60, jb.meanDepthMs)
        repeat(35) { put(seqN++, frameN++, tag = 1) }
        stream(1)
        assertTrue(jb.shed > 0)
        jb.reset()
        assertEquals(listOf(0, 0, 0), listOf(jb.shed, jb.maxDepthMs, jb.meanDepthMs))
    }
}
