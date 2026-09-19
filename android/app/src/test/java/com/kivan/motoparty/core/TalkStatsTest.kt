package com.kivan.motoparty.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TalkStatsTest {
    private fun stats(seqSpan: Long, received: Int, keepalives: Int) = TalkStats(
        captured = 300, sent = 250, received = received, played = 240, seqSpan = seqSpan,
        late = 2, fec = 3, plc = 7, keepalives = keepalives, jitterTargetMs = 60,
    )

    @Test
    fun lostIsTheSpanMinusReceivedMinusKeepalives() {
        assertEquals(5L, stats(seqSpan = 260, received = 245, keepalives = 10).lost)
    }

    @Test
    fun keepaliveSeqsAreNotLost() {
        // A talk that was all keepalives but for one packet: nothing is missing.
        assertEquals(0L, stats(seqSpan = 12, received = 1, keepalives = 11).lost)
    }

    @Test
    fun lostIsNeverNegative() {
        // A duplicated packet counts twice in `received`.
        assertEquals(0L, stats(seqSpan = 10, received = 11, keepalives = 0).lost)
        assertEquals(0L, stats(seqSpan = 0, received = 0, keepalives = 0).lost)
    }

    @Test
    fun lineFormatIsWhatTheBenchParses() {
        assertEquals(
            "talk stats: tx 250 sent of 300 captured (50 DTX), rx 245 received, 240 played, " +
                "5 lost, 2 late, 3 FEC, 7 PLC, 10 keepalives, jitter target 60 ms",
            stats(seqSpan = 260, received = 245, keepalives = 10).line(),
        )
    }
}
