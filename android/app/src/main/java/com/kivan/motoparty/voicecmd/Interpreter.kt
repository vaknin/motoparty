package com.kivan.motoparty.voicecmd

/**
 * Understands a phrase the grammar does not parse (PROTOCOL.md "Commands", *Interpretation*).
 * One back end today, [CloudGemini]; Gemini Nano on the phone was ruled out (LLM-COMMANDS.md).
 */
fun interface Interpreter {
    /**
     * The model's answer text for [phrase] (for [com.kivan.motoparty.core.Interpretation.commandText]),
     * or a [Failed] saying why there is none. Never throws, except for cancellation. With [asked],
     * [album] is the current track's, when known. [phrase] is the reply to a question of ours (PROTOCOL.md *The clarifying question*).
     */
    suspend fun interpret(phrase: String, lang: String, playing: String?, album: String?, upNext: List<String>, asked: Asked?): Answer

    /** The first request and the question it got. */
    data class Asked(val phrase: String, val question: String)

    sealed interface Answer
    data class Text(val text: String) : Answer
    /** [why] is for the log: "timeout", "rate limited", "HTTP 500", … */
    data class Failed(val why: String) : Answer
}

/**
 * The free tier's requests-per-minute limit, kept on our side, and the pause a 429 asks for.
 * A phrase that may not be sent is not queued: it is conversation. Pure and clock-injected.
 */
class RateGuard(private val perMinute: Int = CloudGemini.REQUESTS_PER_MINUTE) {
    private val sent = ArrayDeque<Long>()
    private var heldUntil = 0L

    /** Whether a request may go out at [nowMs]; if so it is counted. */
    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs < heldUntil) return false
        while (sent.isNotEmpty() && nowMs - sent.first() >= 60_000) sent.removeFirst()
        if (sent.size >= perMinute) return false
        sent.addLast(nowMs)
        return true
    }

    /** Gemini said to wait (HTTP 429): nothing goes out for [ms] from [nowMs]. */
    @Synchronized
    fun hold(nowMs: Long, ms: Long) {
        heldUntil = maxOf(heldUntil, nowMs + ms)
    }
}
