package com.kivan.motoparty.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The one thread every audio-route and voice-engine change runs on.
 *
 * Why it exists: [AudioRouter.enterCall]/[AudioRouter.exitCall] talk to the Bluetooth stack and
 * block for 0.5–1.3 s, and [VoiceEngine.stop] joins two audio threads. Doing that on Main stalled
 * the host for ~2.7 s per talk open and close, which delayed the host's own music resume and made
 * the next drift checks re-seek. Doing it on *any* background thread instead would break the
 * ordering that [AudioRouter]'s reference count and [VoiceEngine]'s start/stop depend on, so all of it
 * goes onto a single serial executor:
 *
 * - every [post] runs to completion before the next one starts, in submission order, so an
 *   open → close → open sequence can never interleave or reorder, and a close requested while an
 *   open is still in flight runs after it and leaves the phone in media mode;
 * - a queued block always runs, even if the coroutine that was waiting for it is cancelled, so a
 *   `enterCall` / `exitCall` pair can never come apart;
 * - failures never die on this thread: they are reported back on the caller's (Main) scope.
 */
class AudioThread(
    private val scope: CoroutineScope,
    /** Called on [scope] (Main) when a posted block throws and has no handler of its own. */
    private val onFailure: (what: String, e: Throwable) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "motoparty-audio").apply { isDaemon = true }
    }

    /**
     * Queues [block] on the audio thread. The returned job completes when the block has run (or
     * failed); joining it is optional and cancelling the joiner does not cancel the work.
     * [onError], when given, replaces the shared failure handler for this block; both run on Main.
     */
    fun post(what: String, onError: ((Throwable) -> Unit)? = null, block: () -> Unit): Job {
        val done = Job()
        val task = Runnable {
            try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "$what failed", e)
                scope.launch { (onError ?: { t: Throwable -> onFailure(what, t) })(e) }
            } finally {
                done.complete()
            }
        }
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "$what dropped: audio thread is shut down")
            done.complete()
        }
        return done
    }

    /** Stops accepting work. Blocks already queued still run; this call does not wait for them. */
    fun shutdown() {
        executor.shutdown()
    }

    private companion object {
        const val TAG = "AudioThread"
    }
}
