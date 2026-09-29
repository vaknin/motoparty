package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AudioRouter.choose]: a headset wins as it always did; with none, the built-in speaker instead of
 * the earpiece (2026-09-29 device run: a talk at the desk with no earbuds was inaudible).
 */
class AudioRouterChoiceTest {

    @Test
    fun `no headset goes to the speaker, not the earpiece`() {
        assertEquals(TYPE_BUILTIN_SPEAKER, AudioRouter.choose(listOf(TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER)))
        assertEquals(TYPE_BUILTIN_SPEAKER, AudioRouter.choose(listOf(TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE)))
    }

    @Test
    fun `any headset beats the speaker`() {
        val phone = listOf(TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER)
        assertEquals(TYPE_BLUETOOTH_SCO, AudioRouter.choose(phone + TYPE_BLUETOOTH_SCO))
        assertEquals(TYPE_BLE_HEADSET, AudioRouter.choose(phone + TYPE_BLE_HEADSET))
        assertEquals(TYPE_WIRED_HEADSET, AudioRouter.choose(phone + TYPE_WIRED_HEADSET))
        assertEquals(TYPE_USB_HEADSET, AudioRouter.choose(phone + TYPE_USB_HEADSET))
    }

    @Test
    fun `headsets keep their order`() {
        assertEquals(TYPE_BLE_HEADSET, AudioRouter.choose(listOf(TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_BUILTIN_SPEAKER)))
        assertEquals(TYPE_BLUETOOTH_SCO, AudioRouter.choose(listOf(TYPE_USB_HEADSET, TYPE_WIRED_HEADSET, TYPE_BLUETOOTH_SCO)))
        assertEquals(TYPE_WIRED_HEADSET, AudioRouter.choose(listOf(TYPE_USB_HEADSET, TYPE_WIRED_HEADSET)))
    }

    @Test
    fun `nothing usable leaves it to the phone`() {
        assertNull(AudioRouter.choose(emptyList()))
        assertNull("the earpiece alone is the phone's default anyway", AudioRouter.choose(listOf(TYPE_BUILTIN_EARPIECE)))
    }
}
