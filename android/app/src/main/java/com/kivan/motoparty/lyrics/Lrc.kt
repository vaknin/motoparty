package com.kivan.motoparty.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One word of a line and when it is sung (track position, ms). */
@Serializable
data class LyricWord(val ms: Long, val text: String)

/** One timed line. Empty [text] and no [words]: an instrumental break. */
@Serializable
data class LyricLine(val ms: Long, val text: String, val words: List<LyricWord> = emptyList())

/** The `200` body of `GET /lyrics/<id>.json` (PROTOCOL.md "Tracks", Lyrics). */
@Serializable
data class LyricsBody(val id: String, val source: String = SOURCE_LRCLIB, val lines: List<LyricLine>) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        const val SOURCE_LRCLIB = "lrclib"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Unknown fields are ignored, as a client must. */
        fun decode(text: String): LyricsBody = json.decodeFromString(serializer(), text)
    }
}

/**
 * LRC to timed lines and word times, exactly per PROTOCOL.md "Tracks", Lyrics (the shared vectors
 * are fixtures/lyrics.json): both phones must light the same word at the same moment. Pure.
 */
object Lrc {
    /** ASCII digits only. */
    private val STAMP = Regex("""\[([0-9]+):([0-9]+)(?:\.([0-9]+))?]""")

    /** A word's start, when its line's words fit before the next line. */
    const val MS_PER_CODE_POINT = 75L

    fun parse(lrc: String): List<LyricLine> {
        val timed = ArrayList<Pair<Long, String>>()
        for (raw in lrc.split('\n')) {
            val line = raw.removeSuffix("\r")
            val stamps = ArrayList<Long>()
            var pos = 0
            // Any number of stamps, from the very first character: one after leading whitespace does not count.
            while (true) {
                val m = STAMP.matchAt(line, pos) ?: break
                stamps += stampMs(m) ?: break
                pos = m.range.last + 1
            }
            if (stamps.isEmpty()) continue
            val text = line.substring(pos).trim(' ', '\t')
            for (ms in stamps) timed += ms to text
        }
        // sortedBy is stable: equal times keep their source order.
        val sorted = timed.sortedBy { it.first }
        return sorted.mapIndexed { i, (ms, text) -> LyricLine(ms, text, words(ms, text, sorted.getOrNull(i + 1)?.first)) }
    }

    /** `m*60000 + ss*1000 + f`, the fraction padded or cut to three digits; null for absurd numbers. */
    private fun stampMs(m: MatchResult): Long? {
        val (min, sec, frac) = m.destructured
        if (min.length > 9 || sec.length > 9) return null
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + (if (frac.isEmpty()) 0L else frac.take(3).padEnd(3, '0').toLong())
    }

    /** Split on runs of spaces and tabs only; lengths in code points; integer arithmetic. */
    private fun words(ms: Long, text: String, nextMs: Long?): List<LyricWord> {
        val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val lengths = parts.map { it.codePointCount(0, it.length).toLong() }
        val total = lengths.sum()
        val squeeze = nextMs != null && ms + MS_PER_CODE_POINT * total > nextMs
        var before = 0L
        return parts.mapIndexed { i, w ->
            val at = if (squeeze) ms + before * (nextMs!! - ms) / total else ms + MS_PER_CODE_POINT * before
            before += lengths[i]
            LyricWord(at, w)
        }
    }

    /**
     * How believable the timing is, 0..1: the share of sung lines that leave a singable amount of
     * time before the next line. Many LRCLIB entries copy one bad sync where lines are 0.7 s
     * apart; those score low. (From chordhand's `LrcParser`.)
     */
    fun plausibility(lines: List<LyricLine>): Double {
        var judged = 0
        var ok = 0
        for (i in 0 until lines.size - 1) {
            val chars = lines[i].text.length
            if (chars < 8) continue
            val gapSec = (lines[i + 1].ms - lines[i].ms) / 1000.0
            if (gapSec > 12) continue
            judged++
            if (chars / gapSec.coerceAtLeast(0.01) <= MAX_CHARS_PER_SEC) ok++
        }
        return if (judged == 0) 0.0 else ok.toDouble() / judged
    }

    /** Fast singing is about 15 characters a second; 22 leaves room for fast verses. */
    private const val MAX_CHARS_PER_SEC = 22.0
}

/**
 * Where the lyrics are at lyrics position `t` (PROTOCOL.md "Timeline"): [line] is the index of the
 * last line with `ms <= t` (-1 before the first), [sung] how many of its words have `ms <= t`.
 */
data class LyricsPosition(val line: Int, val sung: Int) {
    companion object {
        val NONE = LyricsPosition(-1, 0)

        fun at(lines: List<LyricLine>, t: Long): LyricsPosition {
            // The last line with ms <= t: binary search over the sorted times.
            var lo = 0
            var hi = lines.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (lines[mid].ms <= t) lo = mid + 1 else hi = mid
            }
            val i = lo - 1
            if (i < 0) return NONE
            return LyricsPosition(i, lines[i].words.count { it.ms <= t })
        }
    }
}
