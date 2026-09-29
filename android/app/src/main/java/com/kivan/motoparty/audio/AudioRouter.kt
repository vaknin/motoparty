package com.kivan.motoparty.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Call mode for talk and dictation: MODE_IN_COMMUNICATION plus the headset as communication
 * device, which moves AirPods from A2DP to HFP (their mic only works there); with no headset, the
 * built-in speaker rather than the earpiece ([choose]). [exitCall]
 * undoes both so music goes back to A2DP.
 *
 * [enterCall]/[exitCall] block for 0.5–1.3 s and must be called from the single [AudioThread],
 * never from Main: that is what keeps the reference count and the phone's mode consistent when
 * talk and the recognizer overlap (the announcer speaks with USAGE_ASSISTANT and never enters
 * call mode). The `@Synchronized` below is belt and braces.
 */
class AudioRouter(
    context: Context,
    /**
     * Called on the audio thread when this app takes a call route ([enterCall] at nesting depth
     * 0 → 1), before the blocking work. [MediaCue] uses it to know that a sound on the media route
     * has something to wait for, and that a CLOSED earcon still waiting has gone stale (F9b).
     */
    private val onRouteHeld: () -> Unit = {},
    /**
     * Called on the audio thread right after the communication device was cleared, i.e. when this
     * app's call route is gone. [ScoWatch.onRouteReleased] hangs on it: the framework's own
     * "communication device changed" callback for the teardown arrives hundreds of ms later, and a
     * talk opened in that window must not see the old link as still up (F8).
     *
     * `wasSco` is whether the route just given up was a Bluetooth SCO one — captured before
     * [selectedType] is cleared, because only an SCO teardown takes real time and only then does
     * a media-route sound have to wait for the framework's next device (F9b, [MediaCue]).
     */
    private val onRouteReleased: (wasSco: Boolean) -> Unit = {},
) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var depth = 0

    /**
     * What the call route ended up on, for the UI. Published from the audio thread rather than
     * queried: `AudioManager.getCommunicationDevice()` is a binder call into the audio service,
     * which the route change we are making holds locks in, so asking from Main (once a second,
     * for the status) could block Main for exactly as long as the switch takes.
     */
    @Volatile
    var selectedDevice: String? = null
        private set

    /**
     * The [AudioDeviceInfo] type the call route ended up on (a headset, else the built-in speaker —
     * [choose]), or null when we left it to the phone. Published from the audio thread like [selectedDevice].
     */
    @Volatile
    var selectedType: Int? = null
        private set

    /**
     * Does this route still have to bring a Bluetooth SCO link up? Then the route is *not* usable
     * when [enterCall] returns — the link follows ~1 s later, which is what [ScoWatch] watches and
     * [LiveCue] waits for. A BLE headset, a wired one or the earpiece are usable at once.
     */
    val needsSco: Boolean get() = selectedType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /** Reference counted: talk and a voice command may overlap. */
    @Synchronized
    fun enterCall() {
        if (depth++ > 0) return
        // Before anything blocking: from here on this app is in call mode, whether or not the
        // device selection below succeeds, and a media-route sound has to wait for the release.
        runCatching { onRouteHeld() }
        val t = StepTimer()
        // What the route went to, appended to the timing line:
        // `enterCall 3 ms (setMode 1, devices 0, setCommunicationDevice 2) → speaker (no headset)`.
        var chosen = ""
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            t.step("setMode")
            if (Build.VERSION.SDK_INT >= 31) {
                val available = am.availableCommunicationDevices
                val type = choose(available.map { it.type })
                val device = available.firstOrNull { it.type == type }
                t.step("devices")
                if (device != null) {
                    val ok = am.setCommunicationDevice(device)
                    t.step("setCommunicationDevice")
                    Log.i(TAG, "communication device ${describe(device)}: $ok")
                    if (ok) {
                        selectedDevice = describe(device)
                        selectedType = device.type
                    }
                    chosen = " → ${ScoRule.describe(device.type)}" +
                        (if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) " (no headset)" else "") +
                        (if (ok) "" else " refused")
                } else {
                    chosen = " → phone default (no headset, no speaker)"
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                t.step("startBluetoothSco")
                selectedDevice = "Bluetooth SCO"
                // The legacy path asks for SCO whatever is connected; treat it as an SCO route,
                // so the live earcon waits for the link there too (minSdk 29, untested since).
                selectedType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                chosen = " → bt_sco (legacy)"
            }
        } finally {
            Log.i(TAG, t.line("enterCall") + chosen)
        }
    }

    /**
     * Balances one [enterCall], including one that threw: [enterCall] counts itself first, so
     * every caller pairs it with this call whether or not it succeeded.
     */
    @Synchronized
    fun exitCall() {
        if (depth == 0 || --depth > 0) return
        // Read it before the clear below nulls it: only an SCO teardown makes a media-route sound
        // wait for the framework's next communication device (F9b).
        val wasSco = needsSco
        val t = StepTimer()
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
                t.step("clearCommunicationDevice")
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                t.step("stopBluetoothSco")
            }
        } finally {
            // The count is already zero, so this is the last chance to leave call mode.
            am.mode = AudioManager.MODE_NORMAL
            t.step("setMode")
            selectedDevice = null
            selectedType = null
            // Whether or not the clear above threw: this app no longer holds a call route.
            runCatching { onRouteReleased(wasSco) }
            Log.i(TAG, "back to media mode")
            Log.i(TAG, t.line("exitCall"))
        }
    }

    /** Back to media mode whatever is still open. Last thing the host does before shutting down. */
    @Synchronized
    fun exitAll() {
        if (depth == 0) return
        depth = 1
        exitCall()
    }

    private fun describe(d: AudioDeviceInfo) = "${d.productName} (type ${d.type})"

    companion object {
        private const val TAG = "AudioRouter"

        /**
         * Which of the phone's available communication device types the call route goes to. Pure,
         * unit-tested by `AudioRouterChoiceTest`.
         *
         * A headset wins, in [preference] order, exactly as before. With none — no earbuds, no
         * wired set — the **built-in speaker**, not the phone's own default: left alone,
         * MODE_IN_COMMUNICATION plays talk and the talk earcons through the earpiece, which cannot
         * be heard with the phone on a desk or on the handlebar (2026-09-29 device run:
         * `Earcons: live routed to earpiece`). Null only when the phone offers neither; the
         * framework then keeps its default.
         */
        fun choose(types: List<Int>): Int? =
            types.filter { preference(it) < Int.MAX_VALUE }.minByOrNull { preference(it) }
                ?: types.firstOrNull { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        private fun preference(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
            AudioDeviceInfo.TYPE_USB_HEADSET -> 3
            else -> Int.MAX_VALUE
        }
    }
}
