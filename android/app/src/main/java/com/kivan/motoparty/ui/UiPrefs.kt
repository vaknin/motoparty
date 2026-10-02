package com.kivan.motoparty.ui

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What only the screens care about, kept apart from [com.kivan.motoparty.Settings] (which the
 * host reads): nothing here changes what the link, the music or talk do.
 */
@Immutable
data class UiPrefs(
    /**
     * The screen stays on while the Ride tab is showing and the host runs. Off by default: there
     * is no charger on the bike, and the floating button and the notification work with it off.
     */
    val keepScreenOn: Boolean = false,
    /** The Developer section of Settings was unlocked (seven taps on the version). */
    val developer: Boolean = false,
    /**
     * Per-track lyrics offset in ms by track id (PROTOCOL.md "Tracks", Lyrics): the lyrics
     * position is `positionMs - offset`, so a negative one shows them sooner. Only non-zero ones,
     * the most recently set last, at most [MAX_LYRICS_OFFSETS].
     */
    val lyricsOffsets: Map<String, Int> = emptyMap(),
) {
    fun lyricsOffset(id: String): Int = lyricsOffsets[id] ?: 0

    /** [id]'s offset set to [ms] (0 forgets it). */
    fun withLyricsOffset(id: String, ms: Int): UiPrefs {
        val m = LinkedHashMap(lyricsOffsets)
        m.remove(id)
        if (ms != 0) m[id] = ms.coerceIn(-MAX_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        while (m.size > MAX_LYRICS_OFFSETS) m.remove(m.keys.first())
        return copy(lyricsOffsets = m)
    }

    companion object {
        const val LYRICS_OFFSET_STEP_MS = 200
        const val MAX_LYRICS_OFFSET_MS = 10_000
        const val MAX_LYRICS_OFFSETS = 300

        /** `id:ms,id:ms` (track ids are `[A-Za-z0-9_-]`). */
        fun encodeOffsets(m: Map<String, Int>): String = m.entries.joinToString(",") { "${it.key}:${it.value}" }

        fun decodeOffsets(s: String?): Map<String, Int> = s.orEmpty().split(',').mapNotNull { e ->
            val i = e.lastIndexOf(':')
            if (i <= 0) null else e.substring(i + 1).toIntOrNull()?.let { e.substring(0, i) to it }
        }.toMap(LinkedHashMap())
    }
}

class UiPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(
        UiPrefs(
            keepScreenOn = prefs.getBoolean("keepScreenOn", false),
            developer = prefs.getBoolean("developer", false),
            lyricsOffsets = UiPrefs.decodeOffsets(prefs.getString("lyricsOffsets", null)),
        ),
    )
    val flow: StateFlow<UiPrefs> = _flow

    fun update(change: (UiPrefs) -> UiPrefs) {
        val p = change(_flow.value)
        prefs.edit()
            .putBoolean("keepScreenOn", p.keepScreenOn)
            .putBoolean("developer", p.developer)
            .putString("lyricsOffsets", UiPrefs.encodeOffsets(p.lyricsOffsets))
            .apply()
        _flow.value = p
    }
}
