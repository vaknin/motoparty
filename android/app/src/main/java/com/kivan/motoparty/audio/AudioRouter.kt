package com.kivan.motoparty.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Call mode for talk and dictation: MODE_IN_COMMUNICATION plus the headset as communication
 * device, which moves AirPods from A2DP to HFP (their mic only works there). [exitCall]
 * undoes both so music goes back to A2DP.
 *
 * [enterCall]/[exitCall] block for 0.5–1.3 s and must be called from the single [AudioThread],
 * never from Main: that is what keeps the reference count and the phone's mode consistent when
 * talk and the recognizer overlap (the announcer speaks with USAGE_ASSISTANT and never enters
 * call mode). The `@Synchronized` below is belt and braces.
 */
class AudioRouter(context: Context) {
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

    /** Reference counted: talk and a voice command may overlap. */
    @Synchronized
    fun enterCall() {
        if (depth++ > 0) return
        val t = StepTimer()
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            t.step("setMode")
            if (Build.VERSION.SDK_INT >= 31) {
                val device = am.availableCommunicationDevices.minByOrNull { preference(it.type) }
                t.step("devices")
                if (device != null && preference(device.type) < Int.MAX_VALUE) {
                    val ok = am.setCommunicationDevice(device)
                    t.step("setCommunicationDevice")
                    Log.i(TAG, "communication device ${describe(device)}: $ok")
                    if (ok) selectedDevice = describe(device)
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                t.step("startBluetoothSco")
                selectedDevice = "Bluetooth SCO"
            }
        } finally {
            Log.i(TAG, t.line("enterCall"))
        }
    }

    /**
     * Balances one [enterCall], including one that threw: [enterCall] counts itself first, so
     * every caller pairs it with this call whether or not it succeeded.
     */
    @Synchronized
    fun exitCall() {
        if (depth == 0 || --depth > 0) return
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

    private fun preference(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        else -> Int.MAX_VALUE
    }

    private fun describe(d: AudioDeviceInfo) = "${d.productName} (type ${d.type})"

    companion object {
        private const val TAG = "AudioRouter"
    }
}
