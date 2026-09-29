package com.kivan.motoparty.music

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-route latency trim. First two-phone run (2026-09-29): the one trim, set to ~260 ms for
 * the AirPods, was applied with both phones on their speakers and put the Pixel ~240 ms ahead.
 */
class LatencyTrimsTest {
    private val airpods = OutputRoute("AA:BB:CC:DD:EE:01", "AirPods Pro", bluetooth = true)
    private val other = OutputRoute("AA:BB:CC:DD:EE:02", "Cardo", bluetooth = true)

    @Test
    fun `the old single value becomes the Bluetooth default and the speaker starts at 0`() {
        val t = LatencyTrims.load(local = null, bluetoothDefault = null, devices = null, legacy = 260)
        assertEquals(260, t.of(airpods))
        assertEquals(260, t.of(other))
        assertEquals(0, t.of(OutputRoute.SPEAKER))
        assertEquals(0, t.of(OutputRoute(OutputRoute.LOCAL_KEY, "Wired headphones", bluetooth = false)))
    }

    @Test
    fun `stored values win over the legacy one`() {
        val t = LatencyTrims.load(local = 20, bluetoothDefault = 100, devices = "AA:BB:CC:DD:EE:01=240", legacy = 260)
        assertEquals(240, t.of(airpods))
        assertEquals(100, t.of(other))
        assertEquals(20, t.of(OutputRoute.SPEAKER))
    }

    @Test
    fun `editing one route leaves the others alone`() {
        val t = LatencyTrims(bluetoothDefault = 260)
            .with(airpods, 250)
            .with(OutputRoute.SPEAKER, -30)
        assertEquals(250, t.of(airpods))
        assertEquals(260, t.of(other))
        assertEquals(-30, t.of(OutputRoute.SPEAKER))
        assertEquals(LatencyTrims.MAX_MS, t.with(other, 9_000).of(other))
    }

    @Test
    fun `the device map survives a round trip, and a bad pair is skipped`() {
        val map = mapOf("AA:BB:CC:DD:EE:01" to 240, "name:Cardo" to -20)
        assertEquals(map, LatencyTrims.decode(LatencyTrims.encode(map)))
        assertEquals(mapOf("x" to 5), LatencyTrims.decode("x=5;junk;=3;y=z"))
        assertEquals(emptyMap<String, Int>(), LatencyTrims.decode(""))
    }

    private val speaker = MediaRoute.Out(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Pixel 8")
    private val earpiece = MediaRoute.Out(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "", "Pixel 8")
    private val a2dp = MediaRoute.Out(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB:CC:DD:EE:01", "AirPods Pro")
    private val sco = MediaRoute.Out(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB:CC:DD:EE:01", "AirPods Pro")
    private val usb = MediaRoute.Out(AudioDeviceInfo.TYPE_USB_HEADSET, "card=1", "USB-C Audio")

    @Test
    fun `the framework's media device wins`() {
        assertEquals(OutputRoute.SPEAKER, MediaRoute.pick(listOf(speaker), listOf(earpiece, speaker, a2dp)))
        assertEquals(airpods, MediaRoute.pick(listOf(a2dp), listOf(earpiece, speaker, a2dp)))
    }

    @Test
    fun `SCO is the same Bluetooth device, so a talk does not change the trim`() {
        assertEquals(airpods, MediaRoute.pick(listOf(sco), listOf(earpiece, speaker, a2dp, sco)))
        // The earpiece while the call route is held is not a media answer: fall back.
        assertEquals(airpods, MediaRoute.pick(listOf(earpiece), listOf(earpiece, speaker, a2dp, sco)))
    }

    @Test
    fun `without an answer, Bluetooth then wired then the speaker`() {
        assertEquals(airpods, MediaRoute.pick(null, listOf(earpiece, speaker, usb, a2dp)))
        assertEquals(
            OutputRoute(OutputRoute.LOCAL_KEY, "USB audio", bluetooth = false),
            MediaRoute.pick(emptyList(), listOf(earpiece, speaker, usb)),
        )
        assertEquals(OutputRoute.SPEAKER, MediaRoute.pick(null, listOf(earpiece, speaker)))
    }

    @Test
    fun `a Bluetooth device without an address is keyed by its name`() {
        val r = MediaRoute.pick(null, listOf(a2dp.copy(address = "")))
        assertEquals(OutputRoute("name:AirPods Pro", "AirPods Pro", bluetooth = true), r)
    }
}
