package com.kivan.motoparty

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings, persisted in SharedPreferences and observable. */
data class Settings(
    /** During talk: duck music instead of pausing it (PROTOCOL.md default: pause). */
    val duckDuringTalk: Boolean = false,
    /** Lead time for the post-talk `music.play`, covering the HFP -> A2DP switch. */
    val resumeLeadMs: Int = 1500,
    /** This phone's Bluetooth output latency; playback runs this far ahead of the anchor. */
    val latencyTrimMs: Int = 0,
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
            latencyTrimMs = prefs.getInt("latencyTrimMs", d.latencyTrimMs),
            asrLanguage = prefs.getString("asrLanguage", d.asrLanguage) ?: d.asrLanguage,
            overlayEnabled = prefs.getBoolean("overlayEnabled", d.overlayEnabled),
            captureDump = prefs.getBoolean("captureDump", d.captureDump),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_flow.value)
        prefs.edit()
            .putBoolean("duckDuringTalk", s.duckDuringTalk)
            .putInt("resumeLeadMs", s.resumeLeadMs.coerceIn(0, 5000))
            .putInt("latencyTrimMs", s.latencyTrimMs.coerceIn(-500, 500))
            .putString("asrLanguage", s.asrLanguage)
            // Gone 2026-09-29 (the command mode; headset presses as talk triggers, the earbuds
            // sit inside the helmet). Old stored values are never read; drop them on first save.
            .remove("headsetNext")
            .remove("headsetPlayPause")
            .putBoolean("overlayEnabled", s.overlayEnabled)
            .putBoolean("captureDump", s.captureDump)
            .apply()
        _flow.value = load()
    }
}
