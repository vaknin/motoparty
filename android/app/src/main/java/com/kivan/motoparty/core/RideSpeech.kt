package com.kivan.motoparty.core

/**
 * DIBS.md "What dibs says": when a line from dibs is spoken into the rider's headset. Nothing is
 * spoken while the phone is busy (a talk, a phone call, a message being read back). A question is
 * spoken as soon as it isn't; anything else waits for the next song to start, or for the music to
 * stop, bundled with the others that waited, and never longer than [holdMaxMs]. "dibs, quiet"
 * keeps everything in the dibs app and speaks nothing. Every line is cut to one short sentence
 * with no code. Pure and clock-injected; Main only in the app.
 */
class RideSpeech(private val holdMaxMs: Long = HOLD_MAX_MS) {
    /** One line of dibs's chat: [id] is dibs's, [speak] its short spoken form. */
    data class Line(val id: String, val speak: String, val question: Boolean = false)

    var quiet = false
        private set
    private val held = mutableListOf<Line>()
    private var heldSinceMs: Long? = null
    /** Lines that came while quiet: said as a count when talking again. */
    private var missed = 0
    /** dibs's ids already taken, newest last; a line comes once however often the chat is resent. */
    private val seen = LinkedHashSet<String>()

    /** Lines are waiting to be spoken. */
    val holding: Boolean get() = held.isNotEmpty()

    /** A line arrived at [nowMs]. Returns what to say now: one string, or null. */
    fun onLine(line: Line, nowMs: Long, playing: Boolean, busy: Boolean): String? {
        if (!seen.add(line.id)) return null
        while (seen.size > SEEN_MAX) seen.remove(seen.first())
        val text = spoken(line.speak) ?: return null
        if (quiet) {
            missed++
            return null
        }
        held += line.copy(speak = text)
        if (heldSinceMs == null) heldSinceMs = nowMs
        return tick(nowMs, playing, busy)
    }

    /** A song started: what waited is said now, unless the phone is [busy]. */
    fun onSongStart(busy: Boolean): String? = if (busy) null else flush()

    /** Called now and then, and on every change: what is due is said. */
    fun tick(nowMs: Long, playing: Boolean, busy: Boolean): String? {
        val since = heldSinceMs ?: return null
        if (busy) return null
        val due = !playing || held.any { it.question } || nowMs - since >= holdMaxMs
        return if (due) flush() else null
    }

    /** "dibs, quiet": drops what was held (it stays in the app). Returns the acknowledgement. */
    fun quiet(): String {
        quiet = true
        missed += held.size
        held.clear()
        heldSinceMs = null
        return "Quiet. dibs stays in the app."
    }

    /** "dibs, talk": speaking again, with a count of what came meanwhile. */
    fun talk(): String {
        val n = missed
        quiet = false
        missed = 0
        return when (n) {
            0 -> "Talking again."
            1 -> "Talking again. One line came meanwhile; it's in the app."
            else -> "Talking again. $n lines came meanwhile; they're in the app."
        }
    }

    /** Questions first, then the rest in order; at most [BUNDLE_MAX] lines, the rest counted. */
    private fun flush(): String? {
        heldSinceMs = null
        if (held.isEmpty()) return null
        val ordered = held.filter { it.question } + held.filterNot { it.question }
        held.clear()
        val out = ordered.take(BUNDLE_MAX).map { if (it.question) "dibs asks: ${it.speak} It waits in the app." else "dibs: ${it.speak}" }
        val more = ordered.size - BUNDLE_MAX
        return (out + listOfNotNull("And $more more in the app.".takeIf { more > 0 })).joinToString(" ")
    }

    companion object {
        const val HOLD_MAX_MS = 5 * 60_000L
        const val BUNDLE_MAX = 3
        const val WORDS_MAX = 25
        private const val SEEN_MAX = 200
        private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val code = Regex("""`[^`]*`|https?://\S+|\S*[/\\]\S+\.\w+\S*""")
        private val marks = Regex("""[*_#>|{}<]""")
        private val spaces = Regex("""\s+""")
        private val beforeMark = Regex("""\s+([.,!?;:])""")
        /** Up to a full stop before a capital or the end, so "e.g." or "No." does not end it. */
        private val firstSentence = Regex("""^.*?[.!?](?=\s+\p{Lu}|$)""")

        /**
         * One short sentence fit for a helmet: no code, links or paths, at most [WORDS_MAX] words,
         * ending in a full stop; null when nothing speakable is left.
         */
        fun spoken(text: String): String? {
            val plain = text.replace(link, "$1").replace(code, " ").replace(marks, " ")
                .replace(spaces, " ").replace(beforeMark, "$1").trim()
            if (plain.none { it.isLetter() }) return null
            val sentence = firstSentence.find(plain)?.value ?: plain
            val words = sentence.split(' ')
            val cut = if (words.size > WORDS_MAX) words.take(WORDS_MAX).joinToString(" ").trimEnd(',', ';', ':', '-') + "…" else sentence
            return if (cut.last() in ".!?…") cut else "$cut."
        }
    }
}
