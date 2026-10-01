package com.kivan.motoparty.voicecmd

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject

/**
 * Understands a phrase the grammar does not parse (PROTOCOL.md "Commands", *Interpretation*).
 * [CloudGemini] per model, raced by [FirstAnswer]; Gemini Nano on the phone was ruled out (LLM-COMMANDS.md).
 */
fun interface Interpreter {
    /**
     * The model's answer text (for [com.kivan.motoparty.core.Interpretation.outcome]) for the
     * context [window] ([com.kivan.motoparty.music.VoiceWindow]: the phrase and what is playing),
     * or a [Failed] saying why there is none. Never throws, except for cancellation. A window with
     * `asked` is the reply to a question of ours (PROTOCOL.md *The clarifying question*).
     */
    suspend fun interpret(window: JsonObject): Answer

    sealed interface Answer
    /** [by] names the back end that answered, for the log. */
    data class Text(val text: String, val by: String? = null) : Answer
    /** [why] is for the log: "timeout", "rate limited", "HTTP 500", … */
    data class Failed(val why: String) : Answer
}

/**
 * Asks every back end in [all] at once and returns the first [Interpreter.Text]; the others are
 * cancelled. [backup] joins only when it can help: once every back end in [all] has failed, or when
 * none has answered after [backupAfterMs]. Nothing is waited for past [limitMs]. When all fail, the
 * reasons are joined in the order the back ends were asked, each under its name; one still
 * running at [limitMs] is a "timeout". A reply to a question goes only to the back ends not named
 * in [noReplies]. Each back end keeps its own time limit and rate guard.
 */
class FirstAnswer(
    private val all: List<Pair<String, Interpreter>>,
    private val noReplies: Set<String> = emptySet(),
    private val backup: Pair<String, Interpreter>? = null,
    private val backupAfterMs: Long = 0,
    private val limitMs: Long = Long.MAX_VALUE,
) : Interpreter {
    override suspend fun interpret(window: JsonObject): Interpreter.Answer {
        val reply = window["asked"] != null
        fun usable(it: Pair<String, Interpreter>) = !reply || it.first !in noReplies
        val asking = mutableListOf<String>()
        val failures = mutableMapOf<String, String>()
        val answer = withTimeoutOrNull(limitMs) {
            coroutineScope {
                var pending = listOf<Pair<String, Deferred<Interpreter.Answer>>>()
                fun ask(backend: Pair<String, Interpreter>) {
                    asking += backend.first
                    pending = pending + (backend.first to async { backend.second.interpret(window) })
                }
                all.filter(::usable).forEach(::ask)
                var spare = backup?.takeIf(::usable)
                val timer = spare?.let { async { delay(backupAfterMs) } }
                while (pending.isNotEmpty() || spare != null) {
                    if (pending.isEmpty()) { ask(spare!!); spare = null; continue }
                    val got = select {
                        pending.forEach { (name, call) -> call.onAwait { name to it } }
                        if (spare != null) timer!!.onAwait { null }
                    }
                    if (got == null) { ask(spare!!); spare = null; continue }
                    val (name, answer) = got
                    pending = pending.filterNot { it.first == name }
                    when (answer) {
                        is Interpreter.Text -> {
                            pending.forEach { it.second.cancel() }
                            timer?.cancel()
                            return@coroutineScope answer
                        }
                        is Interpreter.Failed -> failures[name] = answer.why
                    }
                }
                timer?.cancel()
                null
            }
        }
        return answer ?: Interpreter.Failed(asking.joinToString("; ") { "$it: ${failures[it] ?: "timeout"}" })
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
