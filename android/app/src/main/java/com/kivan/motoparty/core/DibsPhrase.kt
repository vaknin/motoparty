package com.kivan.motoparty.core

/**
 * DIBS.md "Saying it": a rider's phrase that starts with the word "dibs" is for dibs, the user's
 * coding assistant, not for the music. Checked before the grammar and the interpreter, so a
 * message never goes to Gemini. Pure; Main only in the app.
 */
object DibsPhrase {
    sealed interface Said {
        /** Send [text] to dibs (the words after "dibs", as recognised, first letter upper-case). */
        data class Message(val text: String) : Said
        /** "dibs, quiet": nothing from dibs is spoken until "dibs, talk" or the service stops. */
        data object Quiet : Said
        /** "dibs, talk": dibs's lines are spoken again. */
        data object Talk : Said
    }

    const val WORD = "dibs"
    private val fillers = setOf("hey", "okay", "ok")
    private val quiet = setOf("quiet", "be quiet", "stop talking", "shut up", "silence")
    private val talk = setOf("talk", "talk again", "you can talk", "speak", "unmute")
    private val cancels = setOf("cancel", "cancel that", "cancel it", "no", "no cancel", "scrap that", "never mind", "dont send", "don't send")
    /**
     * The raw text's leading filler and "dibs", with the punctuation around them. Letters and digits
     * spelled out, not `\W`/`\b`, which differ between the JVM and Android's ICU on other scripts.
     */
    private val prefix = Regex("""^[^\p{L}\p{N}]*(?:(?:hey|okay|ok)[^\p{L}\p{N}]+)?dibs(?![\p{L}\p{N}])[^\p{L}\p{N}]*""", RegexOption.IGNORE_CASE)

    /** Is [raw] for dibs: its first word (after "hey" or "okay") is "dibs" and something follows? */
    fun parse(raw: String): Said? {
        val words = CommandParser.normalize(raw).split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (words.firstOrNull() in fillers) words.removeAt(0)
        if (words.firstOrNull() != WORD) return null
        val rest = words.drop(1).joinToString(" ")
        if (rest.isEmpty()) return null
        if (rest in quiet) return Said.Quiet
        if (rest in talk) return Said.Talk
        val text = raw.replaceFirst(prefix, "").trim()
        if (text.isEmpty()) return null
        return Said.Message(text.replaceFirstChar { it.uppercaseChar() })
    }

    /** A phrase in the read-back window that drops the message: "cancel", "no", "never mind". */
    fun isCancel(raw: String): Boolean = CommandParser.normalize(raw) in cancels

    /** What the rider hears before it goes: their own words back. */
    fun readBack(text: String): String = "To dibs: $text"
}
