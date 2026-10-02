package com.kivan.motoparty.link

/**
 * The rider's cellular calls while riding (2026-10-02). Pure state machine with an injected clock;
 * the host turns its callbacks into the player's local mute, the talk close, a local-only
 * announcement and the Lark listen window. Main only, like [TalkController].
 *
 * What the user asked for, and why it is shaped like this:
 * - The music stops **only for the rider**: [muteLocal] silences this phone's player and nothing
 *   goes on the wire, so the passenger's iPhone plays on and the shared timeline never moves.
 *   Unmuting at [onIdle] is the whole of "rejoin at the live position": the player never stopped.
 * - The caller is announced **only in the rider's headset** ([announceLocal]), once at the ring
 *   and once more after [REPEAT_MS] if it is still ringing. The name usually arrives a moment
 *   after the ring ([onCaller]); the first announcement waits for it at most [NAME_WAIT_MS].
 * - The rider answers or declines hands-free by voice on the Lark ([onPhrase], while it rings) or
 *   with the Ride screen's buttons ([answer], [decline], [end]). There is no auto-answer.
 *
 * The telephony states can arrive out of order or twice (the callback and the broadcast race, a
 * listener registered mid-call): OFFHOOK without RINGING is an outgoing call (or one answered
 * elsewhere) and is muted without an announcement; a repeated state is ignored.
 */
class CallController(
    private val clock: () -> Long,
    private val muteLocal: (Boolean) -> Unit,
    private val closeTalk: () -> Unit,
    /** Spoken on this phone only, never sent to the client. */
    private val announceLocal: (Caller?) -> Unit,
    private val startListen: () -> Unit,
    private val stopListen: () -> Unit,
    private val accept: () -> Unit,
    private val reject: () -> Unit,
    private val hangUp: () -> Unit,
    /** Anything the Ride screen shows changed ([state], [caller]). */
    private val onChange: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    enum class State { IDLE, RINGING, OFFHOOK }

    /** Who is calling: a contact [name], or only the [number] ("" = withheld), or nothing known. */
    data class Caller(val name: String?, val number: String?)

    var state = State.IDLE
        private set

    /** The caller of the current call, once known; null while unknown and after the call. */
    var caller: Caller? = null
        private set

    /** The current call is one we never saw ring (outgoing, or answered before we watched). */
    var outgoing = false
        private set

    /** The local player is silenced by this call. True from RINGING/OFFHOOK until IDLE. */
    val muted: Boolean get() = state != State.IDLE

    /** Is the Lark listen window open (between [startListen] and [stopListen])? */
    var listening = false
        private set

    private var ringAtMs = 0L
    private var announced = 0
    private var lastAnnounceAtMs = 0L
    /** A spoken or pressed answer/decline was already sent for this ring: no second one. */
    private var decided = false

    fun onRinging() {
        if (state != State.IDLE) {
            // A second ring report (the broadcast after the callback), or a waiting call during
            // a call: neither changes what the rider hears.
            log("call: ringing again in $state, ignored")
            return
        }
        state = State.RINGING
        ringAtMs = clock()
        announced = 0
        decided = false
        outgoing = false
        log("call: ringing, music muted here only")
        muteLocal(true)
        closeTalk()
        listening = true
        startListen()
        onChange()
    }

    /** The caller became known (usually 0.1–1 s after the ring). */
    fun onCaller(c: Caller) {
        if (state == State.IDLE) return
        if (c == caller) return
        caller = c
        if (state == State.RINGING && announced == 0) announce()
        onChange()
    }

    /** Call it every few hundred ms while not idle: the name wait and the repeat run from here. */
    fun tick() {
        if (state != State.RINGING) return
        val now = clock()
        when {
            announced == 0 && now - ringAtMs >= NAME_WAIT_MS -> announce()
            announced == 1 && now - lastAnnounceAtMs >= REPEAT_MS -> announce()
        }
    }

    fun onOffhook() {
        when (state) {
            State.OFFHOOK -> return
            State.RINGING -> {
                log("call: answered")
                stopListening()
            }
            State.IDLE -> {
                log("call: off-hook without a ring (outgoing), music muted here only")
                outgoing = true
                muteLocal(true)
                closeTalk()
            }
        }
        state = State.OFFHOOK
        onChange()
    }

    fun onIdle() {
        if (state == State.IDLE) return
        log("call: ended ($state), music back here at the live position")
        stopListening()
        state = State.IDLE
        caller = null
        outgoing = false
        muteLocal(false)
        onChange()
    }

    /**
     * A phrase heard on the Lark while it rings. Only the first answer or decline counts; anything
     * else is ignored (talking to oneself under the helmet is not a decline).
     */
    fun onPhrase(text: String): CallPhrase.Decision? {
        if (state != State.RINGING || decided) return null
        val d = CallPhrase.match(text) ?: return null
        decide(d, "voice")
        return d
    }

    /** The Ride screen's Answer button. */
    fun answer() {
        if (state == State.RINGING && !decided) decide(CallPhrase.Decision.ACCEPT, "button")
    }

    /** The Ride screen's Decline button. */
    fun decline() {
        if (state == State.RINGING && !decided) decide(CallPhrase.Decision.REJECT, "button")
    }

    /** The Ride screen's End button; while it rings it is a decline. */
    fun end() {
        when (state) {
            State.OFFHOOK -> {
                log("call: hang up (button)")
                hangUp()
            }
            State.RINGING -> decline()
            State.IDLE -> Unit
        }
    }

    private fun decide(d: CallPhrase.Decision, by: String) {
        decided = true
        log("call: ${if (d == CallPhrase.Decision.ACCEPT) "answer" else "decline"} ($by)")
        // Nothing more to hear: the radio takes over (answer) or the ring stops (decline).
        stopListening()
        if (d == CallPhrase.Decision.ACCEPT) accept() else reject()
        onChange()
    }

    private fun announce() {
        announced++
        lastAnnounceAtMs = clock()
        announceLocal(caller)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        stopListen()
    }

    companion object {
        /** The ring is announced once more this long after the first announcement. */
        const val REPEAT_MS = 8_000L
        /** How long the first announcement waits for the caller's name. */
        const val NAME_WAIT_MS = 1_500L
    }
}

/** What the rider said while it rang, and what is spoken for a ring. Pure. */
object CallPhrase {
    enum class Decision { ACCEPT, REJECT }

    private val ACCEPT = setOf(
        "answer", "accept", "yes", "pick up", "take it", "answer it",
        // Hebrew: ענה / תענה (answer), קבל (accept), כן (yes).
        "ענה", "תענה", "קבל", "כן",
    )
    private val REJECT = setOf(
        "decline", "reject", "ignore", "no", "hang up", "deny",
        // Hebrew: דחה / תדחה (decline), לא (no), נתק (hang up).
        "דחה", "תדחה", "לא", "נתק",
    )
    /** "don't answer", "do not pick up": a negated accept is a decline. */
    private val NEGATIONS = setOf("don't", "dont", "not", "never")

    /** Longer phrases are conversation that happens to contain a word, not a reply to the ring. */
    const val MAX_WORDS = 5

    /**
     * The first answer or decline word in [text], whole words only ("unknown" is not "no"), in a
     * phrase of at most [MAX_WORDS] words; null when there is none.
     */
    fun match(text: String): Decision? {
        val words = text.lowercase()
            .replace('’', '\'')
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_WORDS) return null
        var negated = false
        for (i in words.indices) {
            val two = if (i + 1 < words.size) words[i] + " " + words[i + 1] else null
            val hit = when {
                two != null && two in ACCEPT -> Decision.ACCEPT
                two != null && two in REJECT -> Decision.REJECT
                words[i] in ACCEPT -> Decision.ACCEPT
                words[i] in REJECT -> Decision.REJECT
                else -> null
            }
            if (hit != null) return if (negated && hit == Decision.ACCEPT) Decision.REJECT else hit
            if (words[i] in NEGATIONS || words[i] == "do" && words.getOrNull(i + 1) == "not") negated = true
        }
        return null
    }

    /** True when [s] has a Hebrew letter: a Hebrew contact name is spoken by the Hebrew voice. */
    fun isHebrew(s: String): Boolean = s.any { it in 'א'..'ת' }

    /**
     * What is spoken for a ring, and in which language: English, or Hebrew when the name is
     * Hebrew or the rider's recognition language is ([language], a BCP 47 tag). An English voice
     * reads Hebrew letters as nothing at all.
     */
    fun announcement(c: CallController.Caller?, language: String): Pair<String, String> {
        val hebrew = language.startsWith("he") || language.startsWith("iw") || c?.name?.let(::isHebrew) == true
        val name = c?.name?.takeIf { it.isNotBlank() }
        return if (hebrew) {
            when {
                name != null -> "שיחה מ$name"
                c != null -> "שיחה ממספר לא ידוע"
                else -> "שיחה נכנסת"
            } to "he-IL"
        } else {
            when {
                name != null -> "Call from $name"
                c != null -> "Call from unknown number"
                else -> "Incoming call"
            } to (if (language.startsWith("en")) language else "en-US")
        }
    }

    /** The name the call card shows: the contact, else the number, else a plain label. */
    fun label(c: CallController.Caller?): String {
        c?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            c == null -> "Incoming call"
            c.number.isNullOrBlank() -> "Unknown number"
            else -> c.number
        }
    }
}
