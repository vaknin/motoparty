package com.kivan.motoparty.core

/** Parsed voice command, PROTOCOL.md "Commands". */
sealed interface Command {
    data class Play(val kind: Kind, val query: String) : Command
    data object Pause : Command
    data object Resume : Command
    data object Next : Command
    data object Previous : Command
    data object VolumeUp : Command
    data object VolumeDown : Command
    /** "over" / "end talk" / "hang up": close the talk and change nothing else. */
    data object End : Command
    /** "what's playing": announce the current track (2026-09-30). */
    data object NowPlaying : Command
    /** "shuffle": shuffle the upcoming queue, the current track stays (2026-09-30). */
    data object Shuffle : Command
    /**
     * "queue …": add music to the queue (PROTOCOL.md "Commands", *Queueing by voice*, 2026-09-30).
     * [kind] null = `similar`, music like the current track, and [query] is then empty.
     */
    data class Queue(val where: Where, val count: Int?, val kind: Kind?, val query: String) : Command
    data object Unknown : Command

    /** Where queued tracks go; [word] is the grammar's, null for the end (no word). */
    enum class Where(val word: String?) { END(null), NEXT("next"), INSTEAD("instead") }

    enum class Kind(val word: String) { SONG("song"), ALBUM("album"), ARTIST("artist"), PLAYLIST("playlist") }
}

object CommandParser {
    private val kinds = Command.Kind.entries.associateBy { it.word }

    private val wheres = Command.Where.entries.filter { it.word != null }.associateBy { it.word }
    const val SIMILAR = "similar"
    const val QUEUE_MAX_COUNT = 50

    /** A `queue` count: one or two ASCII digits, 1..[QUEUE_MAX_COUNT]. */
    private fun count(word: String): Int? =
        word.takeIf { it.length in 1..2 && it.all { c -> c in '0'..'9' } }?.toInt()?.takeIf { it in 1..QUEUE_MAX_COUNT }

    private val phrases: Map<String, Command> = mapOf(
        "pause" to Command.Pause, "stop" to Command.Pause,
        "resume" to Command.Resume, "continue" to Command.Resume,
        "next" to Command.Next, "skip" to Command.Next,
        "previous" to Command.Previous, "back" to Command.Previous,
        "volume up" to Command.VolumeUp, "louder" to Command.VolumeUp,
        "volume down" to Command.VolumeDown, "quieter" to Command.VolumeDown,
        "over" to Command.End, "end talk" to Command.End, "hang up" to Command.End,
        "what's playing" to Command.NowPlaying, "whats playing" to Command.NowPlaying,
        "what is playing" to Command.NowPlaying, "what song is this" to Command.NowPlaying,
        "shuffle" to Command.Shuffle,
    )

    /** PROTOCOL.md "Commands": per code point, keep letters, marks, numbers, `'` and whitespace. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        text.codePoints().forEach { raw ->
            var cp = Character.toLowerCase(raw)
            if (cp == 0x2019) cp = '\''.code
            if (keep(cp) && !Character.isWhitespace(cp)) sb.appendCodePoint(cp) else sb.append(' ')
        }
        return sb.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun keep(cp: Int): Boolean {
        if (cp == '\''.code || Character.isWhitespace(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            -> true
            else -> false
        }
    }

    fun parse(text: String): Command {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.firstOrNull() == "hey" || words.firstOrNull() == "please") words.removeAt(0)
        if (words.lastOrNull() == "please") words.removeAt(words.lastIndex)
        if (words.isEmpty()) return Command.Unknown

        if (words[0] == "play") {
            var rest = words.drop(1)
            val kind: Command.Kind
            if (rest.size >= 2 && rest[0] == "the" && rest[1] in kinds) {
                kind = kinds.getValue(rest[1]); rest = rest.drop(2)
            } else if (rest.isNotEmpty() && rest[0] in kinds) {
                kind = kinds.getValue(rest[0]); rest = rest.drop(1)
            } else {
                kind = Command.Kind.SONG
            }
            return if (rest.isEmpty()) Command.Unknown else Command.Play(kind, rest.joinToString(" "))
        }
        if (words[0] == "queue") {
            var rest = words.drop(1)
            val where = wheres[rest.firstOrNull()]?.also { rest = rest.drop(1) } ?: Command.Where.END
            // A number is a count only in front of a kind or `similar`: "queue 3 doors down" is a song.
            val count = rest.takeIf { it.size >= 2 && (it[1] in kinds || it[1] == SIMILAR) }?.let { count(it[0]) }
            if (count != null) rest = rest.drop(1)
            if (rest == listOf(SIMILAR)) return Command.Queue(where, count, null, "")
            val kind = kinds[rest.firstOrNull()]?.also { rest = rest.drop(1) } ?: Command.Kind.SONG
            return if (rest.isEmpty()) Command.Unknown else Command.Queue(where, count, kind, rest.joinToString(" "))
        }
        return phrases[words.joinToString(" ")] ?: Command.Unknown
    }
}
