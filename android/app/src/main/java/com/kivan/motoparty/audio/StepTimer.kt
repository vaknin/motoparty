package com.kivan.motoparty.audio

/**
 * Per-step wall time of one audio-thread operation, for one log line such as
 * `enterCall 312 ms (setMode 4, devices 2, setCommunicationDevice 290)`: the bench cannot tell
 * from the platform's logs which call a slow route switch spent its time in.
 */
class StepTimer(private val nowNanos: () -> Long = System::nanoTime) {
    private val start = nowNanos()
    private var last = start
    private val steps = ArrayList<Pair<String, Long>>()

    /** Ends the step that started at the previous [step] (or at construction). */
    fun step(name: String) {
        val now = nowNanos()
        steps += name to (now - last) / 1_000_000
        last = now
    }

    /** `<what> <total> ms (<step> <ms>, ...)`; the total counts everything since construction. */
    fun line(what: String): String {
        val total = (nowNanos() - start) / 1_000_000
        return if (steps.isEmpty()) "$what $total ms"
        else "$what $total ms (${steps.joinToString { "${it.first} ${it.second}" }})"
    }
}
