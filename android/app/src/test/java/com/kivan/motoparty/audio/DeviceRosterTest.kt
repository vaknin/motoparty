package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DeviceRoster] is Stage A's other instrument: what the phone thinks it has, and what changed.
 * Its whole job is to be read afterwards, so the lines below are pinned the way `MediaCueTest`
 * pins the cue lines — a bench run compares them with the framework's own `AudioDeviceBroker`
 * lines, and Stage B's first question ("what does the dongle present itself as?") is answered by
 * reading one of them.
 *
 * The roster used here is the 2026-09-20 Pixel 8 with AirPods Pro, which is the bench's phone.
 */
class DeviceRosterTest {
    private val roster = DeviceRoster()

    private val earpiece = DeviceRoster.Dev(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel 8", isInput = false)
    private val speaker = DeviceRoster.Dev(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 8", isInput = false)
    private val builtinMic = DeviceRoster.Dev(3, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 8", isInput = true)
    private val a2dp = DeviceRoster.Dev(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AirPods Pro", isInput = false)
    private val scoIn = DeviceRoster.Dev(10, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro", isInput = true)
    private val scoOut = DeviceRoster.Dev(11, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro", isInput = false)
    /** The dongle of the wired-mic plan, in the shape Stage B has to find out about. */
    private val usbIn = DeviceRoster.Dev(27, AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C Audio", isInput = true)
    private val usbOut = DeviceRoster.Dev(28, AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C Audio", isInput = false)

    private val phone = listOf(earpiece, speaker, builtinMic)

    @Test
    fun `the first look logs the whole roster`() {
        assertEquals(
            listOf(
                "audio devices: in builtin_mic#3 \"Pixel 8\" · " +
                    "out earpiece#1 \"Pixel 8\", speaker#2 \"Pixel 8\"",
            ),
            roster.update(phone),
        )
    }

    @Test
    fun `the same devices in another order are not a change`() {
        roster.update(phone)
        // The framework re-reports on every route change and its order is its own business.
        assertEquals(emptyList<String>(), roster.update(listOf(speaker, builtinMic, earpiece)))
        assertEquals(emptyList<String>(), roster.update(phone))
    }

    @Test
    fun `a cable going in names what it is, on which side, with the id Stage C will steer at`() {
        roster.update(phone)
        assertEquals(
            listOf(
                "audio devices +: in usb_headset#27 \"USB-C Audio\", out usb_headset#28 \"USB-C Audio\"",
                "audio devices: in builtin_mic#3 \"Pixel 8\", usb_headset#27 \"USB-C Audio\" · " +
                    "out earpiece#1 \"Pixel 8\", speaker#2 \"Pixel 8\", usb_headset#28 \"USB-C Audio\"",
            ),
            roster.update(phone + listOf(usbIn, usbOut)),
        )
    }

    @Test
    fun `a cable coming out says so, and the full line after it is the state`() {
        roster.update(phone + listOf(usbIn, usbOut))
        assertEquals(
            listOf(
                "audio devices -: in usb_headset#27 \"USB-C Audio\", out usb_headset#28 \"USB-C Audio\"",
                "audio devices: in builtin_mic#3 \"Pixel 8\" · " +
                    "out earpiece#1 \"Pixel 8\", speaker#2 \"Pixel 8\"",
            ),
            roster.update(phone),
        )
    }

    @Test
    fun `one thing swapped for another reports both sides of the swap`() {
        roster.update(phone + listOf(scoIn, scoOut))
        val lines = roster.update(phone + listOf(usbIn, usbOut))
        assertEquals(3, lines.size)
        assertEquals("audio devices +: in usb_headset#27 \"USB-C Audio\", out usb_headset#28 \"USB-C Audio\"", lines[0])
        assertEquals("audio devices -: in bt_sco#10 \"AirPods Pro\", out bt_sco#11 \"AirPods Pro\"", lines[1])
    }

    @Test
    fun `a device keeping its type but changing its id is a new device`() {
        // It is: the id is the handle AudioRecord.setPreferredDevice takes, and a re-paired headset
        // gets a new one. A roster that hid that would send Stage C at a device that is gone.
        roster.update(listOf(scoIn))
        assertEquals(3, roster.update(listOf(scoIn.copy(id = 44))).size)
    }

    @Test
    fun `a side with nothing on it says none`() {
        assertEquals(
            listOf("audio devices: in none · out bt_a2dp#9 \"AirPods Pro\""),
            roster.update(listOf(a2dp)),
        )
    }

    @Test
    fun `a device with no product name is still named by its type`() {
        assertEquals(
            listOf("audio devices: in none · out usb_device#5"),
            roster.update(listOf(DeviceRoster.Dev(5, AudioDeviceInfo.TYPE_USB_DEVICE, "", isInput = false))),
        )
    }

    @Test
    fun `an unknown type is reported as its number, which is the thing to look up`() {
        assertEquals(
            listOf("audio devices: in none · out type 4242#6 \"Mystery\""),
            roster.update(listOf(DeviceRoster.Dev(6, 4242, "Mystery", isInput = false))),
        )
    }

    @Test
    fun `the UI row is types only, each one once, and nothing at all before the first look`() {
        assertNull("the row shows a dash until the framework has answered", roster.summary())
        roster.update(phone + listOf(scoIn, scoOut, a2dp, usbIn, usbOut))
        assertEquals(
            "in bt_sco, builtin_mic, usb_headset · out earpiece, speaker, bt_sco, bt_a2dp, usb_headset",
            roster.summary(),
        )
    }
}
