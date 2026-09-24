package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo

/**
 * [ScoWatch]'s decision logic, without any state or Android machinery: which signal drives the
 * live earcon's route gate on which API level, and which communication device means *call audio is
 * really flowing over the Bluetooth link*. Unit-tested by `ScoRuleTest`.
 *
 * F8 (2026-09-20): the 2026-09-20 device bench showed the broadcast
 * `ACTION_SCO_AUDIO_STATE_UPDATED` announcing "connected" **534–1100 ms before** the Bluetooth
 * stack opened the SCO link (`SCO_state_change … ->[BTA_AG_SCO_OPEN_ST]`) in 9 of 9 talks: it is
 * the audio framework's *intent*, not the link. The framework's
 * `onCommunicationDeviceChanged(bt_sco)` dispatch landed +184…+530 ms **after** the last
 * `OPEN_ST`, never before it, in every talk — including the 5 of 8 talks where the stack flapped
 * (OPEN → CLOSING 27–47 ms later → OPEN again ~1 s later), which no broadcast reflected at all.
 * So from API 31 on ([COMMUNICATION_DEVICE_SDK], where
 * `AudioManager.addOnCommunicationDeviceChangedListener` exists) that dispatch is the source.
 */
object ScoRule {
    /**
     * `AudioManager.addOnCommunicationDeviceChangedListener` / `getCommunicationDevice()` exist
     * from API 31 (Android 12). Below it only the legacy broadcasts exist — that is also the
     * `startBluetoothSco()` route in [AudioRouter], and minSdk is 29.
     */
    const val COMMUNICATION_DEVICE_SDK = 31

    /** Does the communication-device listener drive the gate here, instead of the broadcasts? */
    fun usesCommunicationDevice(sdkInt: Int): Boolean = sdkInt >= COMMUNICATION_DEVICE_SDK

    /**
     * Is [deviceType] (the type of the current communication device, null for none) a Bluetooth
     * SCO link that is carrying call audio?
     *
     * Only `TYPE_BLUETOOTH_SCO` counts. `TYPE_BLE_HEADSET` deliberately does **not**: the only
     * consumer of this flag is [LiveCue]'s `needsSco` gate, which is on exactly when
     * [AudioRouter.needsSco] is — i.e. when the route we selected *is* `TYPE_BLUETOOTH_SCO`. A BLE
     * headset (like any non-SCO route) is counted as up the moment `enterCall` returns and never
     * consults this flag, so letting a BLE device answer "yes" here could only ever open the gate
     * of an SCO talk with a device that is not its link.
     */
    fun connected(deviceType: Int?): Boolean = deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /**
     * The device type for the log line, in the framework's own spelling (`AS.AudioDeviceBroker:
     * Dispatch onCommunicationDeviceChanged: … type: bt_sco`), so a bench run can line the two up.
     *
     * The names below the headsets are here for [DeviceRoster], which lists *every* device the
     * phone has, not just the one a call route picked — a roster full of `type 18` would be unusable
     * as the bench-readable inventory Stage A of the wired-mic plan asks for. `type N` is still the
     * honest answer for anything unknown: it is the number to look up in `AudioDeviceInfo`, which is
     * exactly what Stage B has to do for the dongle.
     */
    fun describe(deviceType: Int?): String = when (deviceType) {
        null -> "none"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker_safe"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        // The three shapes a USB-C audio dongle can present itself as; which one it is decides the
        // Stage C routing code, and nothing but plugging it in can tell us.
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
        else -> "type $deviceType"
    }
}
