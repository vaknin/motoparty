package com.kivan.motoparty.audio

import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Is the Bluetooth SCO (HFP) audio link up? Cached off Main, like [AudioModeWatch].
 *
 * [AudioRouter.enterCall] returns long before the headset is actually carrying call audio: on the
 * 2026-09-20 AirPods bench `enterCall` took 199–563 ms while the SCO link came up 1.17–1.38 s
 * after the press. The "live" earcon must not be played before that (see [LiveCue]), so the link
 * state is watched here.
 *
 * Two broadcasts, because one of them may no longer exist on this phone:
 * - `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED`, the classic signal. It is documented for the
 *   deprecated `startBluetoothSco()` path; we drive the route with `setCommunicationDevice()`
 *   instead, and **Android 17 moved SCO management from the Bluetooth stack into the audio
 *   framework** ("Audio Managed SCO", source.android.com/docs/core/audio/sco-audio-mgmt), where
 *   device state is reported through `AudioDeviceCallback` rather than "legacy broadcasts". So it
 *   may simply not fire on the Pixel 8 this runs on.
 * - `BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED` (`STATE_AUDIO_CONNECTED`), sent by the Bluetooth
 *   stack itself when the HFP audio connection is established — the same event the bench's
 *   `SCO_state_change: … -> BTA_AG_SCO_OPEN_ST` line comes from. Needs `BLUETOOTH_CONNECT`, which
 *   the app already holds.
 *
 * Neither fires early: both describe the link, not the request to build one. If *both* stay silent
 * the earcon still plays, on [LiveCue]'s fallback timer, and the `live cue:` log line says
 * `fallback` — that is how a bench run tells us which of the two is real on this device.
 *
 * The receiver runs on its own daemon thread (never Main, never the audio thread, which is inside
 * the route switch these broadcasts are about).
 */
class ScoWatch(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** On the watcher thread, only when the state really changed. */
    private val onChange: (connected: Boolean, atMs: Long) -> Unit,
) {
    /** Volatile: written on the watcher thread, read on Main and on the audio thread. */
    @Volatile
    var connected: Boolean = false
        private set

    private var thread: HandlerThread? = null
    private var receiver: BroadcastReceiver? = null

    fun start() {
        val t = HandlerThread("motoparty-sco").apply { isDaemon = true }
        t.start()
        thread = t
        val handler = Handler(t.looper)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val at = clock()
                val action = intent.action ?: return
                val state = when (action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED ->
                        when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> true
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> false
                            else -> return // connecting / error: not a link state
                        }
                    BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED ->
                        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                            BluetoothHeadset.STATE_AUDIO_CONNECTED -> true
                            BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> false
                            else -> return
                        }
                    else -> return
                }
                update(state, at, action.substringAfterLast('.'))
            }
        }
        receiver = r
        // Registering is a binder call into ActivityManager; do it on the watcher thread too, so
        // host start-up never blocks on it. The sticky intent it returns (if the classic broadcast
        // is still alive here) seeds the current state.
        handler.post {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED).apply {
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            }
            val sticky = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(r, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(r, filter, null, handler)
                }
            }.onFailure { Log.w(TAG, "sco receiver refused: $it") }.getOrNull()
            val state = sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) update(true, clock(), "sticky")
        }
    }

    fun stop() {
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null
        thread?.quitSafely()
        thread = null
    }

    private fun update(state: Boolean, atMs: Long, source: String) {
        if (state == connected) return
        connected = state
        Log.i(TAG, "sco ${if (state) "connected" else "disconnected"} ($source)")
        onChange(state, atMs)
    }

    private companion object {
        const val TAG = "ScoWatch"
    }
}
