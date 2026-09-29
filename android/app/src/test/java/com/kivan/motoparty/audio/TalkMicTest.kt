package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TalkMic.choose]: the Lark A1 RX presents itself on the Pixel as `usb_device`, one input, 48 kHz
 * stereo (S4 probe, 2026-09-28). A USB boom-mic headset is `usb_headset` with a mono input and must
 * not be taken for two riders.
 */
class TalkMicTest {
    private val builtin = DeviceRoster.Dev(15, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 8", true, listOf(1, 2))
    private val sco = DeviceRoster.Dev(5, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro", true, listOf(1))
    private val a2dp = DeviceRoster.Dev(6, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AirPods Pro", false, listOf(2))
    private val lark = DeviceRoster.Dev(27, AudioDeviceInfo.TYPE_USB_DEVICE, "Lark A1", true, listOf(2))

    @Test
    fun `the receiver plugged in is a lark talk`() {
        val mic = TalkMic.choose(listOf(builtin, sco, a2dp, lark), enabled = true)
        assertEquals(TalkMic.Lark(27, AudioDeviceInfo.TYPE_USB_DEVICE, "Lark A1"), mic)
        assertEquals("talk mic: lark usb_device#27 \"Lark A1\", swap off", TalkMic.line(mic, swap = false))
        assertEquals("talk mic: lark usb_device#27 \"Lark A1\", swap on", TalkMic.line(mic, swap = true))
    }

    @Test
    fun `the setting off or no roster yet is earbuds`() {
        assertEquals(TalkMic.Earbuds("setting off"), TalkMic.choose(listOf(lark), enabled = false))
        assertEquals(TalkMic.Earbuds("no device list yet"), TalkMic.choose(null, enabled = true))
        assertEquals("talk mic: earbuds (setting off)", TalkMic.line(TalkMic.Earbuds("setting off"), swap = true))
    }

    @Test
    fun `no USB input is earbuds, and a USB output alone does not count`() {
        val usbOut = lark.copy(id = 28, isInput = false)
        assertEquals(TalkMic.Earbuds("no USB input"), TalkMic.choose(listOf(builtin, sco, a2dp, usbOut), enabled = true))
    }

    @Test
    fun `missing warns only when the setting wants a receiver that is not there`() {
        assertEquals("no USB input", TalkMic.missing(listOf(builtin, sco, a2dp), enabled = true))
        assertEquals(null, TalkMic.missing(listOf(builtin, sco, a2dp, lark), enabled = true))
        assertEquals("setting off: no warning", null, TalkMic.missing(listOf(builtin), enabled = false))
        assertEquals("no roster yet: no warning", null, TalkMic.missing(null, enabled = true))
        val headset = DeviceRoster.Dev(30, AudioDeviceInfo.TYPE_USB_HEADSET, "Boom", true, listOf(1))
        assertEquals("USB input usb_headset#30 \"Boom\" is mono", TalkMic.missing(listOf(builtin, headset), enabled = true))
    }

    @Test
    fun `a mono USB headset mic is not two riders`() {
        val headset = DeviceRoster.Dev(30, AudioDeviceInfo.TYPE_USB_HEADSET, "Boom", true, listOf(1))
        val mic = TalkMic.choose(listOf(builtin, headset), enabled = true)
        assertTrue(mic is TalkMic.Earbuds)
        assertEquals("talk mic: earbuds (USB input usb_headset#30 \"Boom\" is mono)", TalkMic.line(mic, false))
    }

    @Test
    fun `empty channel counts mean any, and a usb_device is preferred`() {
        val any = DeviceRoster.Dev(31, AudioDeviceInfo.TYPE_USB_ACCESSORY, "", true, emptyList())
        assertEquals(TalkMic.Lark(31, AudioDeviceInfo.TYPE_USB_ACCESSORY, ""), TalkMic.choose(listOf(any), enabled = true))
        val headset = DeviceRoster.Dev(3, AudioDeviceInfo.TYPE_USB_HEADSET, "Dongle", true, listOf(1, 2))
        assertEquals(27, (TalkMic.choose(listOf(any, headset, lark), enabled = true) as TalkMic.Lark).id)
        assertEquals(3, (TalkMic.choose(listOf(any, headset), enabled = true) as TalkMic.Lark).id)
    }

    @Test
    fun `route check - confirmed, elsewhere, never reported`() {
        val c = LarkRouteCheck(27, graceMs = 2_000)
        assertEquals(LarkRouteCheck.Verdict.PENDING, c.check(null, 0))
        assertEquals(LarkRouteCheck.Verdict.PENDING, c.check(null, 1_999))
        assertEquals(LarkRouteCheck.Verdict.OK, c.check(27, 2_100))
        assertTrue(c.confirmed)
        // A null after the confirmation is the framework reshuffling, not a loss.
        assertEquals(LarkRouteCheck.Verdict.PENDING, c.check(null, 60_000))
        // Unplugged: the recorder falls back to the phone's own mic.
        assertEquals(LarkRouteCheck.Verdict.WRONG, c.check(15, 60_500))

        val never = LarkRouteCheck(27, graceMs = 2_000)
        assertFalse(never.confirmed)
        assertEquals(LarkRouteCheck.Verdict.WRONG, never.check(null, 2_000))
        assertEquals(LarkRouteCheck.Verdict.WRONG, LarkRouteCheck(27).check(15, 0))
    }
}
