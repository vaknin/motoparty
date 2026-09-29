package com.kivan.motoparty.music

import android.media.AudioDeviceInfo

/**
 * The output the music plays on, as far as the latency trim cares: one Bluetooth device (keyed by
 * its address, so its A2DP, SCO and LE Audio faces are the same route), or this phone's own outputs
 * (speaker, wired, USB), which all share [LOCAL_KEY]. [name] is for the Settings row and the log.
 */
data class OutputRoute(val key: String, val name: String, val bluetooth: Boolean) {
    companion object {
        const val LOCAL_KEY = "local"
        val SPEAKER = OutputRoute(LOCAL_KEY, "Phone speaker", bluetooth = false)
    }
}

/**
 * The per-route latency trim (PROTOCOL.md "Music flow" step 4: each phone's own output-latency
 * trim). It used to be one number, set for the AirPods (~260 ms) and applied on every route, so
 * with both phones on their speakers the Pixel ran ~240 ms ahead (first two-phone run, 2026-09-29).
 *
 * [local] is the phone's own outputs; [byDevice] a value per Bluetooth address; a Bluetooth
 * device without its own value gets [bluetoothDefault] — where the old single setting went.
 * Pure: [com.kivan.motoparty.SettingsStore] persists it, LinkHost picks the route.
 */
data class LatencyTrims(
    val local: Int = 0,
    val bluetoothDefault: Int = 0,
    val byDevice: Map<String, Int> = emptyMap(),
) {
    fun of(route: OutputRoute): Int =
        if (route.bluetooth) byDevice[route.key] ?: bluetoothDefault else local

    /** [ms] (clamped) as the trim of [route] from now on. */
    fun with(route: OutputRoute, ms: Int): LatencyTrims {
        val v = ms.coerceIn(MIN_MS, MAX_MS)
        return if (route.bluetooth) copy(byDevice = byDevice + (route.key to v)) else copy(local = v)
    }

    companion object {
        const val MIN_MS = -500
        const val MAX_MS = 500

        /** `key=ms` pairs joined by `;`. Bluetooth addresses and `name:` keys never hold either. */
        fun encode(byDevice: Map<String, Int>): String =
            byDevice.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

        /** The inverse of [encode]; a malformed pair is skipped rather than failing the lot. */
        fun decode(s: String?): Map<String, Int> {
            if (s.isNullOrBlank()) return emptyMap()
            return s.split(';').mapNotNull { pair ->
                val at = pair.lastIndexOf('=')
                if (at <= 0) return@mapNotNull null
                val v = pair.substring(at + 1).toIntOrNull() ?: return@mapNotNull null
                pair.substring(0, at) to v.coerceIn(MIN_MS, MAX_MS)
            }.toMap()
        }

        /**
         * The stored settings, migrating the single `latencyTrimMs` of before 2026-09-29: it was
         * set for a Bluetooth headset, so it becomes the Bluetooth default; the phone's own
         * outputs start at 0.
         */
        fun load(local: Int?, bluetoothDefault: Int?, devices: String?, legacy: Int?): LatencyTrims =
            LatencyTrims(
                local = (local ?: 0).coerceIn(MIN_MS, MAX_MS),
                bluetoothDefault = (bluetoothDefault ?: legacy ?: 0).coerceIn(MIN_MS, MAX_MS),
                byDevice = decode(devices),
            )
    }
}

/**
 * Which output the music is on right now, from what `AudioManager` reports. Pure, so the choice
 * is testable; [com.kivan.motoparty.audio.DeviceWatch] does the asking, off Main.
 */
object MediaRoute {
    /** One output device, flattened out of `AudioDeviceInfo`. */
    data class Out(val type: Int, val address: String, val name: String)

    private val BLUETOOTH = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
    /** Bluetooth types music plays on (SCO is the call profile of the same device). */
    private val BLUETOOTH_MEDIA = BLUETOOTH - AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_DOCK,
    )

    /**
     * [forMedia] is the framework's answer for media (`getAudioDevicesForAttributes(USAGE_MEDIA)`,
     * API 33+; null below), [outputs] every output device. The framework's answer wins when it is
     * a media output; SCO counts as its Bluetooth device. Anything else — nothing, or the earpiece
     * while a talk holds the call route — falls back to the usual priority: Bluetooth media, then
     * wired/USB, then the speaker. That keeps a talk from flipping the route (and the trim) back
     * and forth while the music is paused anyway.
     */
    fun pick(forMedia: List<Out>?, outputs: List<Out>): OutputRoute {
        val framework = forMedia.orEmpty().firstOrNull {
            it.type in BLUETOOTH || it.type in WIRED || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val d = framework
            ?: outputs.firstOrNull { it.type in BLUETOOTH_MEDIA }
            ?: outputs.firstOrNull { it.type in WIRED }
            ?: return OutputRoute.SPEAKER
        return route(d)
    }

    private fun route(d: Out): OutputRoute = when (d.type) {
        in BLUETOOTH -> OutputRoute(
            key = d.address.ifBlank { "name:${d.name}" },
            name = d.name.ifBlank { "Bluetooth" },
            bluetooth = true,
        )
        in WIRED -> OutputRoute(
            OutputRoute.LOCAL_KEY,
            if (d.type in setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)) {
                "USB audio"
            } else {
                "Wired headphones"
            },
            bluetooth = false,
        )
        else -> OutputRoute.SPEAKER
    }
}
