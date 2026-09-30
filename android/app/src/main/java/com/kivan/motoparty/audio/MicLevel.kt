package com.kivan.motoparty.audio

/**
 * The rider's microphone level, for the meter on the Ride tab: the loudest sample (0…32768) of the
 * last 20 ms frame the running capture loop read. The loop stores one int per frame — no
 * allocation, no lock, nothing that can block it — and the screen reads it when it wants to
 * (about 16 times a second, only while a talk is live); it never goes through
 * [com.kivan.motoparty.Hub]. Zero when no talk is capturing.
 */
object MicLevel {
    @Volatile
    @JvmField
    var peak: Int = 0

    /** The loudest sample of [pcm], as [peak] wants it. One pass, no allocation. */
    fun peakOf(pcm: ShortArray): Int {
        var peak = 0
        for (v in pcm) {
            val a = if (v < 0) -v.toInt() else v.toInt()
            if (a > peak) peak = a
        }
        return peak
    }
}
