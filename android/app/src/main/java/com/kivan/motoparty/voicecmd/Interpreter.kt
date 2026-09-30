package com.kivan.motoparty.voicecmd

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Understands a phrase the grammar does not parse (PROTOCOL.md "Commands", *Interpretation*).
 * [CloudGemini] per model, raced by [FirstAnswer]; Gemini Nano on the phone was ruled out (LLM-COMMANDS.md).
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
    /** [by] names the back end that answered, for the log. */
    data class Text(val text: String, val by: String? = null) : Answer
    /** [why] is for the log: "timeout", "rate limited", "HTTP 500", … */
    data class Failed(val why: String) : Answer
}

/**
 * Asks every back end at once and returns the first [Interpreter.Text]; the others are cancelled.
 * When all fail, the reasons are joined in back-end order, each under its back end's name. Each back end keeps its own
 * time limit and rate guard. A reply to a question goes only to the back ends not named in
 * [noReplies].
 */
class FirstAnswer(
    private val all: List<Pair<String, Interpreter>>,
    private val noReplies: Set<String> = emptySet(),
) : Interpreter {
    override suspend fun interpret(phrase: String, lang: String, playing: String?, album: String?, upNext: List<String>, asked: Interpreter.Asked?): Interpreter.Answer =
        coroutineScope {
            val backends = if (asked == null) all else all.filterNot { it.first in noReplies }
            var pending = backends.map { (name, backend) ->
                name to async { backend.interpret(phrase, lang, playing, album, upNext, asked) }
            }
            val failures = mutableMapOf<String, String>()
            while (pending.isNotEmpty()) {
                val (name, answer) = select { pending.forEach { (name, call) -> call.onAwait { name to it } } }
                pending = pending.filterNot { it.first == name }
                when (answer) {
                    is Interpreter.Text -> {
                        pending.forEach { it.second.cancel() }
                        return@coroutineScope answer
                    }
                    is Interpreter.Failed -> failures[name] = answer.why
                }
            }
            Interpreter.Failed(backends.joinToString("; ") { (name, _) -> "$name: ${failures[name]}" })
        }
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
