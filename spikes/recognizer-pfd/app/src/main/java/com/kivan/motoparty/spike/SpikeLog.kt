package com.kivan.motoparty.spike

import android.util.Log

/**
 * Every line of output goes here: logcat tag `Spike` (what run.sh scrapes) and the on-screen
 * TextView (what a human reads when the phone is in hand).
 */
object SpikeLog {
    const val TAG = "Spike"

    @Volatile
    private var sink: ((String) -> Unit)? = null

    private val buffered = StringBuilder()

    fun attach(sink: (String) -> Unit) {
        synchronized(buffered) {
            this.sink = sink
            if (buffered.isNotEmpty()) sink(buffered.toString().trimEnd('\n'))
        }
    }

    fun detach() {
        sink = null
    }

    fun log(line: String) {
        Log.i(TAG, line)
        val s = sink
        synchronized(buffered) {
            buffered.append(line).append('\n')
            if (buffered.length > 64_000) buffered.delete(0, buffered.length - 48_000)
        }
        s?.invoke(line)
    }

    fun warn(line: String) {
        Log.w(TAG, line)
        log0(line)
    }

    fun err(line: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, line, t) else Log.e(TAG, line)
        log0(if (t != null) "$line -- ${t::class.java.simpleName}: ${t.message}" else line)
    }

    private fun log0(line: String) {
        val s = sink
        synchronized(buffered) { buffered.append(line).append('\n') }
        s?.invoke(line)
    }

    /** A stopwatch whose readings are what the PFD test's callback log is made of. */
    class Clock {
        private val t0 = System.nanoTime()
        fun ms(): Long = (System.nanoTime() - t0) / 1_000_000L
        fun stamp(): String = "t=%5d ms".format(ms())
    }
}
