package com.kivan.motoparty.core

/**
 * NTP-style offset estimator, PROTOCOL.md "Clock": `offset = hostClock - localClock`, taken from
 * the lowest-RTT sample among the last [window] (ties: most recent). Negative RTTs are discarded
 * without taking a slot; a sample more than [jumpMs] away from the estimate (the other clock
 * jumped, e.g. after sleep) clears the window first.
 */
class ClockEstimator(private val window: Int = 8, private val jumpMs: Double = 500.0) {
    data class Sample(val rtt: Long, val offset: Double)

    private val samples = ArrayDeque<Sample>()

    /** Returns false if the sample was discarded. */
    fun add(t0: Long, t1: Long, t2: Long, t3: Long): Boolean {
        val rtt = (t3 - t0) - (t2 - t1)
        if (rtt < 0) return false
        val offset = ((t1 - t0) + (t2 - t3)) / 2.0
        val current = this.offset
        if (current != null && kotlin.math.abs(offset - current) > jumpMs) samples.clear()
        samples.addLast(Sample(rtt, offset))
        while (samples.size > window) samples.removeFirst()
        return true
    }

    val best: Sample?
        get() {
            var best: Sample? = null
            for (s in samples) if (best == null || s.rtt <= best.rtt) best = s
            return best
        }

    val offset: Double? get() = best?.offset

    fun hostToLocal(host: Long): Double? = offset?.let { host - it }
    fun localToHost(local: Long): Double? = offset?.let { local + it }

    fun reset() = samples.clear()
}
