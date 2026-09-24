package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScoRule] is F8's decision logic: which signal drives the live earcon's route gate, and which
 * communication device means Bluetooth call audio is really flowing. The device bench
 * (`tools/bench/results/2026-09-20-t3-talk-f7`) is the reason it exists — the "SCO connected"
 * broadcast fired 534–1100 ms before the link, `onCommunicationDeviceChanged(bt_sco)` +184…+530 ms
 * after it.
 */
class ScoRuleTest {

    @Test
    fun `the communication device drives from API 31, the broadcasts below it`() {
        assertFalse("minSdk: only the legacy broadcasts exist", ScoRule.usesCommunicationDevice(29))
        assertFalse(ScoRule.usesCommunicationDevice(30))
        assertTrue("addOnCommunicationDeviceChangedListener exists", ScoRule.usesCommunicationDevice(31))
        assertTrue("the Pixel 8 this runs on", ScoRule.usesCommunicationDevice(37))
    }

    @Test
    fun `only a routed SCO device counts as connected`() {
        assertTrue(ScoRule.connected(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertFalse("no communication device at all", ScoRule.connected(null))
        assertFalse(ScoRule.connected(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertFalse(ScoRule.connected(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(ScoRule.connected(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertFalse(ScoRule.connected(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse("anything unknown is not a link either", ScoRule.connected(-7))
    }

    @Test
    fun `a BLE headset is not an SCO link`() {
        // A BLE route needs no gate at all: AudioRouter.needsSco is false for it and LiveCue counts
        // it up as soon as enterCall returns. Counting it here could only open an *SCO* talk's gate
        // with a device that is not its link.
        assertFalse(ScoRule.connected(AudioDeviceInfo.TYPE_BLE_HEADSET))
    }

    @Test
    fun `the log names devices the way the framework does`() {
        assertEquals("bt_sco", ScoRule.describe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("earpiece", ScoRule.describe(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals("ble_headset", ScoRule.describe(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("none", ScoRule.describe(null))
        assertEquals("type 4242", ScoRule.describe(4242))
    }

    @Test
    fun `the roster's devices have names too, and a USB dongle's three shapes have three`() {
        // DeviceRoster lists every device the phone has, not just a call route's; and which of
        // these three a USB-C audio dongle presents itself as is Stage B's first question.
        assertEquals("usb_headset", ScoRule.describe(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("usb_device", ScoRule.describe(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("usb_accessory", ScoRule.describe(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals("builtin_mic", ScoRule.describe(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("speaker_safe", ScoRule.describe(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        assertEquals("telephony", ScoRule.describe(AudioDeviceInfo.TYPE_TELEPHONY))
        assertEquals("remote_submix", ScoRule.describe(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }
}
