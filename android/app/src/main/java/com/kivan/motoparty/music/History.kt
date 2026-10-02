package com.kivan.motoparty.music

import com.kivan.motoparty.core.SearchKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One search typed on the Search tab: its text and which chip was on ([SearchKind]). */
@Serializable
data class RecentSearch(val kind: String, val query: String)

/**
 * The Search tab's two lists while its box is empty: recent searches and recently played tracks,
 * newest first, de-duplicated. Pure and immutable; [com.kivan.motoparty.HistoryStore] persists it.
 */
@Serializable
data class History(
    val searches: List<RecentSearch> = emptyList(),
    val played: List<Track> = emptyList(),
) {
    /**
     * [query] searched as [kind], moved to the top. The same text (ignoring case and spacing) is one
     * entry whatever the chip was: the newest kind wins, so re-running it gives the latest view.
     */
    fun searched(kind: String, query: String): History {
        val q = query.trim().replace(WHITESPACE, " ")
        if (q.isEmpty() || kind !in KINDS) return this
        val entry = RecentSearch(kind, q)
        return copy(searches = (listOf(entry) + searches.filterNot { it.query.equals(q, ignoreCase = true) }).take(MAX_SEARCHES))
    }

    /** [t] started playing: moved to the top, by id. */
    fun played(t: Track): History =
        copy(played = (listOf(t) + played.filterNot { it.id == t.id }).take(MAX_PLAYED))

    fun withoutSearches(): History = copy(searches = emptyList())

    companion object {
        const val MAX_SEARCHES = 10
        const val MAX_PLAYED = 20
        private val KINDS = setOf(SearchKind.SONGS, SearchKind.ALBUMS, SearchKind.PLAYLISTS, SearchKind.ARTISTS)
        private val WHITESPACE = Regex("\\s+")
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(h: History): String = json.encodeToString(serializer(), h)

        /** The inverse of [encode]. Anything unreadable is an empty history, never a crash at start. */
        fun decode(s: String?): History {
            if (s.isNullOrBlank()) return History()
            val h = runCatching { json.decodeFromString(serializer(), s) }.getOrNull() ?: return History()
            // Re-applied on the way in, so a hand-edited or older file cannot break the limits.
            return History(
                searches = h.searches.filter { it.kind in KINDS && it.query.isNotBlank() }
                    .distinctBy { it.query.lowercase() }.take(MAX_SEARCHES),
                played = h.played.filter { isValidTrackId(it.id) }.distinctBy { it.id }.take(MAX_PLAYED),
            )
        }
    }
}
