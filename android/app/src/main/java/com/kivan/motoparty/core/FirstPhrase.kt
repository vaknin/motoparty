package com.kivan.motoparty.core

/**
 * One phone's in-talk phrase stream → which phrase is a command (PROTOCOL.md "Commands", *The
 * first phrase decides* and *Solo talk*). The opener's first phrase that is not empty after
 * normalisation and arrives within [windowMs] of this phone's live earcon is a command if it
 * parses, conversation otherwise; every later phrase is conversation. The other side never
 * commands; a solo talk makes every non-empty phrase a command, with no window. Pure and
 * clock-injected so the window is testable; Main only in the app. Vectors: `fixtures/first_phrase.json`.
 */
class FirstPhraseGate(private val windowMs: Long = FIRST_PHRASE_MS, private val answerMs: Long = Interpretation.ANSWER_MS) {
    enum class Role {
        /** This phone opened the talk (the side in the host's `talk.open{by}`). */
        OPENER,
        /** The other side opened it: never a command. */
        OTHER,
        /** The host's talk has no client: every non-empty phrase is a command. */
        SOLO,
    }

    var role = Role.OTHER
        private set
    private var liveAtMs: Long? = null
    private var used = false
    private var interpret = false
    /** When the host's question arrived, while its reply is awaited (*The clarifying question*). */
    private var askedAtMs: Long? = null

    /**
     * A new talk opened; [role] is this phone's part in it. The window waits for [live].
     * [interpret]: the host interprets (PROTOCOL.md *Interpretation*), so the opener's first phrase
     * is a candidate whether it parses or not.
     */
    fun open(role: Role, interpret: Boolean = false) {
        this.role = role
        this.interpret = interpret
        liveAtMs = null
        used = false
        askedAtMs = null
    }

    /**
     * The host's clarifying question arrived at [atMs]: the opener's next non-empty phrase within
     * [answerMs] is the reply and is passed on as it is. Nothing changes for the other roles.
     */
    fun ask(atMs: Long) {
        if (role == Role.OPENER) askedAtMs = atMs
    }

    /** This phone's live earcon played at [atMs]: the window starts. Once per talk. */
    fun live(atMs: Long) {
        if (liveAtMs == null) liveAtMs = atMs
    }

    /**
     * A phrase's result arrived at [nowMs]: its normalised text (for [CommandParser.parse]) if it
     * is a command (or, when the host interprets, a candidate), null for conversation. A phrase before the live earcon counts as in the window.
     */
    fun onPhrase(text: String, nowMs: Long): String? {
        val plain = CommandParser.normalize(text)
        if (plain.isEmpty()) return null
        return when (role) {
            Role.SOLO -> plain
            Role.OTHER -> null
            Role.OPENER -> {
                askedAtMs?.let { asked ->
                    askedAtMs = null
                    used = true
                    return plain.takeIf { nowMs <= asked + answerMs }
                }
                if (isSpent(nowMs)) return null
                used = true
                plain.takeIf { interpret || CommandParser.parse(it) != Command.Unknown }
            }
        }
    }

    /**
     * No phrase from [nowMs] on can be a command: the first phrase is used, the window has passed,
     * or this phone is not the opener. The recognizer may stop. Never true in a solo talk.
     */
    fun isSpent(nowMs: Long): Boolean = when (role) {
        Role.SOLO -> false
        Role.OTHER -> true
        Role.OPENER -> askedAtMs?.let { nowMs > it + answerMs } ?: (used || liveAtMs?.let { nowMs > it + windowMs } == true)
    }

    companion object {
        /** PROTOCOL.md `FIRST_PHRASE_MS`: from the live earcon, inclusive. */
        const val FIRST_PHRASE_MS = 8_000L
    }
}
