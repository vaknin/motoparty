package com.kivan.motoparty.core

/**
 * PROTOCOL.md "Commands", *Wake word*: which phrases heard during a talk are commands.
 * Vectors: `fixtures/wake.json`.
 */
object WakeWord {
    private val fillers = setOf("hey", "ok", "okay")

    /** The wake word as whole words, after normalisation. ASR returns "Moto party" (two words). */
    private val forms = listOf(listOf("motoparty"), listOf("moto", "party"), listOf("motor", "party"))

    /**
     * null = not a command (conversation); "" = the bare wake word, which arms the next phrase;
     * anything else = the normalised command text after the wake word, for [CommandParser.parse].
     */
    fun commandText(text: String): String? {
        val words = CommandParser.normalize(text).split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size && words[i] in fillers) i++
        val rest = words.subList(i, words.size)
        val form = forms.firstOrNull { it.size <= rest.size && rest.subList(0, it.size) == it } ?: return null
        return rest.drop(form.size).joinToString(" ")
    }
}

/**
 * One phone's in-talk phrase stream → what each phrase is (PROTOCOL.md "Commands"): a command
 * (with the wake word, right after a bare wake word, or anything at all in a solo talk), the bare
 * wake word that arms the next phrase, or conversation, which is never acted on. Pure and
 * clock-injected so the arming window is testable; Main only in the app.
 */
class PhraseGate(private val armMs: Long = ARM_MS) {
    sealed interface Heard {
        /** [text] is normalised, without the wake word. */
        data class Command(val text: String) : Heard
        data object Armed : Heard
        data object Conversation : Heard
    }

    private var armedUntilMs: Long? = null

    /** [solo]: the host's talk has no client, so the wake word is optional. */
    fun onPhrase(text: String, solo: Boolean, nowMs: Long): Heard {
        val armed = armedUntilMs?.let { nowMs <= it } == true
        armedUntilMs = null
        val wake = WakeWord.commandText(text)
        if (wake == "") {
            armedUntilMs = nowMs + armMs
            return Heard.Armed
        }
        if (wake != null) return Heard.Command(wake)
        if (!armed && !solo) return Heard.Conversation
        val plain = CommandParser.normalize(text)
        return if (plain.isEmpty()) Heard.Conversation else Heard.Command(plain)
    }

    /** A new talk starts unarmed. */
    fun reset() {
        armedUntilMs = null
    }

    companion object {
        /** PROTOCOL.md: the phrase after a bare wake word counts for this long. */
        const val ARM_MS = 5_000L
    }
}
