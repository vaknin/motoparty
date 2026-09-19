package com.kivan.motoparty.core

/**
 * One talk's numbers, logged once at talk stop by `VoiceEngine`. Pure so the loss arithmetic
 * and the line format are unit-tested: a bench parser reads [line], so its wording is fixed.
 *
 * - [received]: audio packets that arrived, late ones included ([late] is a subset of it).
 * - [plc] counts every concealed frame the decoder produced, including the up to 3 PLC frames
 *   at the end of a spurt when the sender goes quiet without a keepalive in between; it is
 *   therefore not a loss count. [lost] is.
 */
data class TalkStats(
    val captured: Long,
    val sent: Long,
    val received: Int,
    val played: Long,
    val seqSpan: Long,
    val late: Int,
    val fec: Int,
    val plc: Int,
    val keepalives: Int,
    val jitterTargetMs: Int,
) {
    /**
     * `seq` values in the span that never arrived. Keepalives share the counter and are not
     * loss. Never negative: a duplicated packet counts twice in [received] and could otherwise
     * push it below zero.
     */
    val lost: Long get() = (seqSpan - received - keepalives).coerceAtLeast(0)

    fun line(): String =
        "talk stats: tx $sent sent of $captured captured (${captured - sent} DTX), " +
            "rx $received received, $played played, $lost lost, " +
            "$late late, $fec FEC, $plc PLC, " +
            "$keepalives keepalives, jitter target $jitterTargetMs ms"
}
