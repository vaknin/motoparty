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
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Is call audio really flowing over the Bluetooth link? Cached off Main, like [AudioModeWatch].
 *
 * [AudioRouter.enterCall] returns long before the headset is actually carrying call audio: on the
 * 2026-09-20 AirPods bench `enterCall` took 199–563 ms while the SCO link came up 1.17–1.38 s
 * after the press. The "live" earcon must not be played before that (see [LiveCue]), so the link
 * state is watched here.
 *
 * **F8 (2026-09-20), the honest source.** F7 believed the two "SCO connected" broadcasts. The
 * device bench then showed that `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED` (the only one that
 * ever fired, 18/18) says "connected" **534–1100 ms before** the Bluetooth stack opens the link,
 * and misses the OPEN → CLOSING → OPEN flap that happened in 5 of 8 talks — it reports the audio
 * framework's intent, not the link. The framework's `onCommunicationDeviceChanged` dispatch does
 * report the link: `type: bt_sco` landed +184…+530 ms after the *last* `BTA_AG_SCO_OPEN_ST` in
 * every talk, never before it, and a non-SCO type follows every close. So from API
 * [ScoRule.COMMUNICATION_DEVICE_SDK] on, `AudioManager.addOnCommunicationDeviceChangedListener`
 * drives this watch; below it the broadcasts still do (untested territory: minSdk 29, and that is
 * also where [AudioRouter] uses the legacy `startBluetoothSco()`).
 *
 * On API 31+ the broadcasts are still received but **only logged**, marked `[not used]`, so a bench
 * run can keep comparing the two timelines.
 *
 * One line per real change, which is what the bench greps:
 * - `sco connected (communication device bt_sco)` / `sco disconnected (communication device
 *   earpiece|none|…)` — the API 31+ source;
 * - `sco disconnected (call route released)` — [onRouteReleased], see below;
 * - `sco connected (ACTION_SCO_AUDIO_STATE_UPDATED|ACTION_AUDIO_STATE_CHANGED|sticky)` — below 31.
 *
 * If the listener never reports, the earcon still plays on [LiveCue]'s fallback timer and the
 * `live cue:` line says `fallback` — that is how a bench run tells us the source is wrong.
 *
 * Everything is registered and reported on one daemon thread (never Main, never the audio thread,
 * which is inside the route switch these events are about); [onRouteReleased] is the one entry
 * point called from elsewhere, so the state update is `@Synchronized`.
 */
class ScoWatch(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /**
     * **Every** communication-device report, raw, off Main, before [update] de-duplicates it
     * (F9b). [MediaCue] needs exactly this: the `earpiece|none` dispatch that follows a teardown is
     * swallowed by the dedupe below, because [onRouteReleased] has already set `connected` to
     * false — so the sound waiting for the media route would never see its signal. Null = the
     * framework reports no communication device. API 31+ only, the source F8 settled on.
     */
    private val onDevice: (type: Int?, atMs: Long) -> Unit = { _, _ -> },
    /** Off Main, only when the state really changed. */
    private val onChange: (connected: Boolean, atMs: Long) -> Unit,
) {
    /** Volatile: written on the watcher thread or the audio thread, read on Main and on the audio thread. */
    @Volatile
    var connected: Boolean = false
        private set

    @ChecksSdkIntAtLeast(api = ScoRule.COMMUNICATION_DEVICE_SDK)
    private val usesDevice = ScoRule.usesCommunicationDevice(Build.VERSION.SDK_INT)

    private var thread: HandlerThread? = null
    private val waitLock = Object()
    private var receiver: BroadcastReceiver? = null
    private var deviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    fun start() {
        val t = HandlerThread("motoparty-sco").apply { isDaemon = true }
        t.start()
        thread = t
        val handler = Handler(t.looper)
        startBroadcasts(handler)
        // Registering is a binder call into the audio service; do it on the watcher thread.
        if (usesDevice) handler.post { startCommunicationDevice(handler) }
    }

    fun stop() {
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null
        if (Build.VERSION.SDK_INT >= ScoRule.COMMUNICATION_DEVICE_SDK) {
            deviceListener?.let { l ->
                runCatching {
                    context.getSystemService(AudioManager::class.java)
                        ?.removeOnCommunicationDeviceChangedListener(l)
                }
            }
        }
        deviceListener = null
        thread?.quitSafely()
        thread = null
    }

    /**
     * `clearCommunicationDevice()` has returned on the audio thread: the call route this app held
     * is gone, so whatever was up is not up any more.
     *
     * Without this, a talk opened right after a previous one closed could poll a **stale**
     * `connected` from the old link and beep at once: the framework's own
     * `onCommunicationDeviceChanged(earpiece)` for that teardown arrives hundreds of ms later,
     * possibly after the new talk's `enterCall` returned. A re-open that *kept* the route (the
     * `TalkAudio: re-open …: call route kept` case) never gets here, so it still sees the link that
     * is genuinely still up and beeps immediately — which is the point of that case.
     *
     * Nothing sets `connected` on `enterCall`: selecting the device is exactly the intent-not-link
     * signal F8 removed.
     */
    /**
     * Blocks the calling (capture) thread until [connected], at most [timeoutMs], or until
     * [stillWanted] turns false; returns [connected]. Woken by the report itself, not by polling
     * the state: [stillWanted] is re-checked every [WAIT_SLICE_MS] only so a talk closed meanwhile
     * lets its capture thread go well inside `VoiceEngine.stop`'s 500 ms join (F9c).
     */
    fun awaitConnected(timeoutMs: Long, stillWanted: () -> Boolean): Boolean {
        val deadline = clock() + timeoutMs
        synchronized(waitLock) {
            while (!connected && stillWanted()) {
                val left = deadline - clock()
                if (left <= 0) break
                waitLock.wait(minOf(left, WAIT_SLICE_MS))
            }
        }
        return connected
    }

    fun onRouteReleased() {
        update(false, clock(), "call route released")
    }

    /**
     * The legacy broadcasts. On API 31+ they no longer decide anything (see the class doc) and are
     * only logged for the bench; below 31 they are all there is.
     */
    private fun startBroadcasts(handler: Handler) {
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
                report(state, at, action.substringAfterLast('.'))
            }
        }
        receiver = r
        // The registration is a binder call into ActivityManager; on the watcher thread too, so
        // host start-up never blocks on it. The sticky intent it returns (if the classic broadcast
        // is still alive here) seeds the current state below 31.
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
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) report(true, clock(), "sticky")
        }
    }

    /** A broadcast: acted on below API 31, logged and dropped above it. */
    private fun report(state: Boolean, atMs: Long, source: String) {
        if (usesDevice) {
            Log.i(TAG, "broadcast sco ${if (state) "connected" else "disconnected"} ($source) [not used]")
        } else {
            update(state, atMs, source)
        }
    }

    /**
     * API 31+: the communication device the audio framework has actually routed call audio to.
     *
     * Runs on the watcher thread and the listener reports there too (its executor is this same
     * handler), which is what makes the one-off read below safe: registering brings no callback for
     * the state that already holds, and any callback for a change racing with the registration is
     * queued *behind* this post.
     */
    @RequiresApi(ScoRule.COMMUNICATION_DEVICE_SDK)
    private fun startCommunicationDevice(handler: Handler) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val executor = Executor { command -> handler.post(command) }
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device -> reportDevice(device?.type) }
        runCatching { am.addOnCommunicationDeviceChangedListener(executor, listener) }
            .onSuccess { deviceListener = listener }
            .onFailure { Log.w(TAG, "communication device listener refused: $it") }
        runCatching { am.communicationDevice }
            .onSuccess { reportDevice(it?.type) }
            .onFailure { Log.w(TAG, "communication device unreadable: $it") }
    }

    /**
     * One report from the framework. The raw [onDevice] callback comes **first and every time**,
     * including for the reports [update] drops as "no change" — that is the whole point of it
     * (F9b); [update] then applies F8's rule, unchanged.
     */
    private fun reportDevice(type: Int?) {
        val at = clock()
        runCatching { onDevice(type, at) }.onFailure { Log.w(TAG, "device listener failed: $it") }
        update(ScoRule.connected(type), at, "communication device ${ScoRule.describe(type)}")
    }

    /** Synchronized: the watcher thread and the audio thread ([onRouteReleased]) both get here. */
    @Synchronized
    private fun update(state: Boolean, atMs: Long, source: String) {
        if (state == connected) return
        connected = state
        synchronized(waitLock) { waitLock.notifyAll() }
        Log.i(TAG, "sco ${if (state) "connected" else "disconnected"} ($source)")
        onChange(state, atMs)
    }

    private companion object {
        const val TAG = "ScoWatch"
        const val WAIT_SLICE_MS = 50L
    }
}
