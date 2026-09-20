package com.kivan.motoparty.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A cached `AudioManager.getMode()`, so Main never asks the audio service for it.
 *
 * Why: `micAvailable()` on Main used to read `audioManager.mode`, a binder call into AudioService
 * — the very service our own [AudioRouter.exitCall] is inside of for 0.5–1.3 s during a talk
 * teardown. On the 2026-09-20 AirPods bench that cost Main ~0.9 s: the client's `talk.open`
 * arrived at 172.07 and was only handled at 172.998, 11 ms after `exitCall` returned, so
 * [TalkAudio]'s "re-open during teardown" collapse never had a chance to run and the route was
 * closed and re-opened back to back.
 *
 * The value is refreshed off Main: on API 31+ by the mode-changed callback (the initial read
 * happens on the watcher thread too), below that by a 1 s poll on the same thread. Either way the
 * answer can be up to ~1 s stale during a route change, which does not matter for the question it
 * answers: *is a cellular call or an incoming ring holding the microphone* (`MODE_IN_CALL` /
 * `MODE_RINGTONE`). `MODE_IN_COMMUNICATION` is our own talk or recognizer and is never "busy".
 */
class AudioModeWatch(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "motoparty-audio-mode").apply { isDaemon = true }
    }

    /** Last mode seen. Volatile: written on the watcher thread, read on Main. */
    @Volatile
    var mode: Int = AudioManager.MODE_NORMAL
        private set

    private var listener: Any? = null

    /** True while the cellular radio owns the microphone (a call in progress, or a ringing one). */
    val inPhoneCall: Boolean
        get() = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE

    fun start() {
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnModeChangedListener { m -> mode = m }
            listener = l
            // Registering and the first read are binder calls as well, so they go to the watcher
            // thread. Callbacks queue behind this block on the same executor, so the read below
            // can never overwrite a newer value.
            executor.execute {
                runCatching { am.addOnModeChangedListener(executor, l) }
                    .onFailure { Log.w(TAG, "mode listener refused: $it") }
                read()
            }
        } else {
            executor.scheduleWithFixedDelay(::read, 0, POLL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 31) {
            (listener as? AudioManager.OnModeChangedListener)?.let {
                runCatching { am.removeOnModeChangedListener(it) }
            }
        }
        listener = null
        executor.shutdownNow()
    }

    private fun read() {
        runCatching { mode = am.mode }.onFailure { Log.w(TAG, "mode read failed: $it") }
    }

    private companion object {
        const val TAG = "AudioModeWatch"
        const val POLL_MS = 1_000L
    }
}
