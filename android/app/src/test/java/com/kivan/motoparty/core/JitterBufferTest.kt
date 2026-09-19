package com.kivan.motoparty.core

import com.kivan.motoparty.core.JitterBuffer.Out
import org.junit.Assert.assertEquals
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

    @Test
    fun firstFramePlaysTargetAfterArrival() {
        put(0, 0); put(1, 1)
        assertEquals(listOf(-2, -2, 0, 1), pulls(4))
    }

    @Test
    fun reorderedPacketsPlayInTsOrder() {
        put(1, 1); put(0, 0); put(3, 3); put(2, 2)
        assertEquals(listOf(-2, -2, 0, 1, 2, 3), pulls(6))
    }

    @Test
    fun lostFrameWithSuccessorUsesFec() {
        put(0, 0); put(1, 1); put(3, 3)
        assertEquals(listOf(-2, -2, 0, 1, -103, 3), pulls(6))
    }

    @Test
    fun lostFramesWithoutImmediateSuccessorConceal() {
        put(0, 0); put(1, 1); put(4, 4)
        // Frames 2 and 3 lost: 2 has no immediate successor (PLC), 3 has one (FEC).
        assertEquals(listOf(-2, -2, 0, 1, -1, -104, 4), pulls(7))
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
        put(seq++, frame++, tag = 0)
        val tags = List(8) { i -> put(seq++, frame++, tag = i + 1); pull().tag() }
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
}
