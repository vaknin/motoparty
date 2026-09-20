package com.kivan.motoparty.spike

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The tests run on a worker thread (they sleep for seconds at a time), but `SpeechRecognizer`
 * is documented as main-thread-only, so every call into it is bounced through here.
 */
object Main {
    val handler = Handler(Looper.getMainLooper())

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        check(done.await(30, TimeUnit.SECONDS)) { "main-thread call timed out" }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
