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
 */
class AudioRouter(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var depth = 0

    val selectedDevice: String?
        get() = if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.let(::describe) else null

    /** Reference counted: talk and a voice command may overlap. */
    @Synchronized
    fun enterCall() {
        if (depth++ > 0) return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val device = am.availableCommunicationDevices.minByOrNull { preference(it.type) }
            if (device != null && preference(device.type) < Int.MAX_VALUE) {
                val ok = am.setCommunicationDevice(device)
                Log.i(TAG, "communication device ${describe(device)}: $ok")
            }
        } else {
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
        }
    }

    @Synchronized
    fun exitCall() {
        if (depth == 0 || --depth > 0) return
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
        }
        am.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "back to media mode")
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
