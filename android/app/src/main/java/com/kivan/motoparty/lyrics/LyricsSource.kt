package com.kivan.motoparty.lyrics

import com.kivan.motoparty.music.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs

/** One `/api/search` result of LRCLIB; only what ranking needs. */
@Serializable
data class LrcCandidate(
    val id: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    /** Seconds. */
    val duration: Double = 0.0,
    val syncedLyrics: String? = null,
)

/**
 * Synced lyrics from LRCLIB (lrclib.net, free, no key). [find] searches by artist and title, and
 * by a plain query when that gives nothing usable. Blocking: call it off Main. [fetch] is the
 * HTTP GET (tests pass canned bodies); it throws [IOException] when LRCLIB cannot be reached.
 */
class LyricsSource(private val fetch: (HttpUrl) -> String) {
    constructor(http: OkHttpClient) : this({ url -> get(http, url) })

    /** The best synced lyrics of [track] as lines, or null when LRCLIB has none that fit. */
    fun find(track: Track): List<LyricLine>? {
        val (artist, title) = clean(track.title, track.artist)
        val byFields = search(BASE.newBuilder().addQueryParameter("artist_name", artist).addQueryParameter("track_name", title).build())
        pick(byFields, track.durationMs)?.let { return it }
        val byQuery = search(BASE.newBuilder().addQueryParameter("q", "$artist $title".trim()).build())
        return pick(byQuery, track.durationMs)
    }

    private fun search(url: HttpUrl): List<LrcCandidate> = parseSearch(fetch(url))

    companion object {
        private val BASE = "https://lrclib.net/api/search".toHttpUrl()
        const val USER_AGENT = "motoparty (github.com/vaknin/motoparty)"
        /** A candidate further than this from the track's length is another recording. */
        const val MAX_DURATION_DIFF_MS = 8_000L
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        private fun get(http: OkHttpClient, url: HttpUrl): String {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("LRCLIB answered ${response.code}")
                return response.body.string()
            }
        }

        fun parseSearch(body: String): List<LrcCandidate> = json.decodeFromString(body)

        /** The lines of the best usable candidate, or null. */
        fun pick(candidates: List<LrcCandidate>, durationMs: Long): List<LyricLine>? =
            rank(candidates, durationMs).firstNotNullOfOrNull { c -> Lrc.parse(c.syncedLyrics!!).takeIf { it.isNotEmpty() } }

        /**
         * Synced candidates, best first (chordhand's `LrclibSource.rank`). Many entries are copies of
         * one bad sync, so believable timing ranks first (in steps of 0.1), then the duration closest
         * to the track's, then distinct syncs only. With a known [durationMs], a candidate more
         * than [MAX_DURATION_DIFF_MS] off is dropped.
         */
        fun rank(candidates: List<LrcCandidate>, durationMs: Long): List<LrcCandidate> {
            val sec = durationMs / 1000.0
            return candidates
                .filter { !it.syncedLyrics.isNullOrBlank() }
                .filter { durationMs <= 0 || abs(it.duration * 1000 - durationMs) <= MAX_DURATION_DIFF_MS }
                .map { it to Lrc.plausibility(Lrc.parse(it.syncedLyrics!!)) }
                .sortedWith(
                    compareByDescending<Pair<LrcCandidate, Double>> { (it.second * 10).toInt() }
                        .thenBy { (c, _) -> if (durationMs > 0) abs(c.duration - sec) else 0.0 },
                )
                .map { it.first }
                .distinctBy { it.syncedLyrics }
        }

        /** Bracketed YouTube noise: "(Official Music Video)", "[Lyric Video]", "(Remastered 2011)", "(HD)". */
        private val NOISE = Regex(
            """\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|remaster(ed)?|hd|hq|4k|mv|explicit)\b[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val FEAT_BRACKETED = Regex("""\s*[(\[]\s*(ft\.?|feat\.?|featuring)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val FEAT_TAIL = Regex("""\s+(ft\.?|feat\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
        private val DASH = Regex("""\s+[-–—]\s+""")
        private val SPACES = Regex("""\s+""")

        /**
         * The artist and title to ask LRCLIB for: YouTube's noise taken off. "Artist - Song" with
         * the track's own artist on the left becomes "Song" (and that spelling of the artist is
         * used); an auto-generated channel's
         * " - Topic" and a "VEVO" suffix leave the artist.
         */
        fun clean(title: String, artist: String): Pair<String, String> {
            val a = artist.replace(Regex("""\s+-\s+Topic$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""VEVO$"""), "")
                .let { it.replace(SPACES, " ").trim() }
            var artistName = a
            var t = title.replace(NOISE, "").replace(FEAT_BRACKETED, "")
            val parts = t.split(DASH, limit = 2)
            if (parts.size == 2 && sameArtist(parts[0], a)) {
                t = parts[1]
                // The title's spelling: "Queen - …" from the channel "Queen Official".
                artistName = parts[0].replace(SPACES, " ").trim()
            }
            t = t.replace(FEAT_TAIL, "").replace(NOISE, "").replace(SPACES, " ").trim()
            return artistName to t.ifEmpty { title.trim() }
        }

        private fun sameArtist(left: String, artist: String): Boolean {
            val l = left.trim().lowercase()
            val r = artist.lowercase()
            if (l.isEmpty() || r.isEmpty()) return false
            // "Pink Floyd" vs "Pink Floyd Official", but not any one letter.
            return l == r || (minOf(l.length, r.length) >= 3 && (l.contains(r) || r.contains(l)))
        }
    }
}
