package com.kivan.motoparty

import android.content.Context
import com.kivan.motoparty.music.LatencyTrims
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings, persisted in SharedPreferences and observable. */
data class Settings(
    /** During talk: duck music instead of pausing it (PROTOCOL.md default: pause). */
    val duckDuringTalk: Boolean = false,
    /** Lead time for the post-talk `music.play`, covering the HFP -> A2DP switch. */
    val resumeLeadMs: Int = 1500,
    /**
     * This phone's output latency per output route; playback runs this far ahead of the anchor.
     * One value per Bluetooth device and one for the phone's own outputs (see [LatencyTrims]).
     */
    val trims: LatencyTrims = LatencyTrims(),
    /** BCP-47 language for speech recognition. */
    val asrLanguage: String = "en-US",
    val overlayEnabled: Boolean = true,
    /**
     * Debug: write every talk's captured microphone PCM to a WAV file under
     * `Android/data/com.kivan.motoparty/files/captures/` (see [com.kivan.motoparty.audio.PcmDump]).
     * Off by default and meant to be turned on for one ride: it is how one microphone is compared
     * with another (Stage A of the wired-mic plan, 2026-09-20). A talk with no client connected is
     * a solo talk whether this is on or not (it is how a command is given alone); this only
     * decides whether its WAV is written.
     */
    val captureDump: Boolean = false,
    /**
     * Host-mic talk (PROTOCOL.md "Host-mic talk", 2026-09-29): with a USB stereo receiver (the
     * Lark A1 RX) plugged in, this phone captures both riders from it — rider left, passenger
     * right — and no phone takes call mode. Off, or with no receiver: the earbud mics as before.
     */
    val larkTalk: Boolean = true,
    /** Swap the receiver's left and right, for when the TX stickers got mixed up. */
    val larkSwap: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow
    val value: Settings get() = _flow.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            duckDuringTalk = prefs.getBoolean("duckDuringTalk", d.duckDuringTalk),
            resumeLeadMs = prefs.getInt("resumeLeadMs", d.resumeLeadMs),
            trims = LatencyTrims.load(
                local = intOrNull("trimLocalMs"),
                bluetoothDefault = intOrNull("trimBluetoothDefaultMs"),
                devices = prefs.getString("trimDevices", null),
                // Before 2026-09-29: one value for every route, set for the AirPods.
                legacy = intOrNull("latencyTrimMs"),
            ),
            asrLanguage = prefs.getString("asrLanguage", d.asrLanguage) ?: d.asrLanguage,
            overlayEnabled = prefs.getBoolean("overlayEnabled", d.overlayEnabled),
            captureDump = prefs.getBoolean("captureDump", d.captureDump),
            larkTalk = prefs.getBoolean("larkTalk", d.larkTalk),
            larkSwap = prefs.getBoolean("larkSwap", d.larkSwap),
        )
    }

    private fun intOrNull(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null

    fun update(change: (Settings) -> Settings) {
        val s = change(_flow.value)
        prefs.edit()
            .putBoolean("duckDuringTalk", s.duckDuringTalk)
            .putInt("resumeLeadMs", s.resumeLeadMs.coerceIn(0, 5000))
            .putInt("trimLocalMs", s.trims.local.coerceIn(LatencyTrims.MIN_MS, LatencyTrims.MAX_MS))
            .putInt("trimBluetoothDefaultMs", s.trims.bluetoothDefault.coerceIn(LatencyTrims.MIN_MS, LatencyTrims.MAX_MS))
            .putString("trimDevices", LatencyTrims.encode(s.trims.byDevice))
            // Migrated into trimBluetoothDefaultMs by load().
            .remove("latencyTrimMs")
            .putString("asrLanguage", s.asrLanguage)
            // Gone 2026-09-29 (the command mode; headset presses as talk triggers, the earbuds
            // sit inside the helmet). Old stored values are never read; drop them on first save.
            .remove("headsetNext")
            .remove("headsetPlayPause")
            .putBoolean("overlayEnabled", s.overlayEnabled)
            .putBoolean("captureDump", s.captureDump)
            .putBoolean("larkTalk", s.larkTalk)
            .putBoolean("larkSwap", s.larkSwap)
            .apply()
        _flow.value = load()
    }
}
