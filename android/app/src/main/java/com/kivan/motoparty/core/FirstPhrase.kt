package com.kivan.motoparty.core

/**
 * One phone's in-talk phrase stream → which phrase is a command (PROTOCOL.md "Commands", *The
 * first phrase decides* and *Solo talk*). The opener's first phrase that is not empty after
 * normalisation and arrives within [windowMs] of this phone's live earcon is a command if it
 * parses, conversation otherwise; every later phrase is conversation. The other side never
 * commands; a solo talk makes every non-empty phrase a command, with no window. Pure and
 * clock-injected so the window is testable; Main only in the app. Vectors: `fixtures/first_phrase.json`.
 */
class FirstPhraseGate(private val windowMs: Long = FIRST_PHRASE_MS) {
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

    /** A new talk opened; [role] is this phone's part in it. The window waits for [live]. */
    fun open(role: Role) {
        this.role = role
        liveAtMs = null
        used = false
    }

    /** This phone's live earcon played at [atMs]: the window starts. Once per talk. */
    fun live(atMs: Long) {
        if (liveAtMs == null) liveAtMs = atMs
    }

    /**
     * A phrase's result arrived at [nowMs]: its normalised text (for [CommandParser.parse]) if it
     * is a command, null for conversation. A phrase before the live earcon counts as in the window.
     */
    fun onPhrase(text: String, nowMs: Long): String? {
        val plain = CommandParser.normalize(text)
        if (plain.isEmpty()) return null
        return when (role) {
            Role.SOLO -> plain
            Role.OTHER -> null
            Role.OPENER -> {
                if (isSpent(nowMs)) return null
                used = true
                plain.takeIf { CommandParser.parse(it) != Command.Unknown }
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
        Role.OPENER -> used || liveAtMs?.let { nowMs > it + windowMs } == true
    }

    companion object {
        /** PROTOCOL.md `FIRST_PHRASE_MS`: from the live earcon, inclusive. */
        const val FIRST_PHRASE_MS = 8_000L
    }
}
