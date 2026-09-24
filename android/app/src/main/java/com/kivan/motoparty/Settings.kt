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
    /** Headset play/pause: "talk" toggles talk, "music" toggles music playback. */
    val headsetPlayPause: String = "talk",
    /** Headset next (AirPods double press): "command" starts a voice command, "next" skips. */
    val headsetNext: String = "command",
    val overlayEnabled: Boolean = true,
    /**
     * Debug: write every talk's captured microphone PCM to a WAV file under
     * `Android/data/com.kivan.motoparty/files/captures/` (see [com.kivan.motoparty.audio.PcmDump]).
     * Off by default and meant to be turned on for one ride: it is how one microphone is compared
     * with another (Stage A of the wired-mic plan, 2026-09-20). While it is on, a talk trigger
     * with no client connected opens a solo talk instead of being refused, so a microphone can be
     * recorded on a ride alone.
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
            headsetPlayPause = prefs.getString("headsetPlayPause", d.headsetPlayPause) ?: d.headsetPlayPause,
            headsetNext = prefs.getString("headsetNext", d.headsetNext) ?: d.headsetNext,
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
            .putString("headsetPlayPause", s.headsetPlayPause)
            .putString("headsetNext", s.headsetNext)
            .putBoolean("overlayEnabled", s.overlayEnabled)
            .putBoolean("captureDump", s.captureDump)
            .apply()
        _flow.value = load()
    }
}
