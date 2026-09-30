package com.kivan.motoparty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * PROTOCOL.md "Commands", *Interpretation*: the interpreter's answer → a command text of the
 * grammar, so an interpreted phrase is executed exactly like a spoken one, or a clarifying
 * question (*The clarifying question*). Pure; vectors: `fixtures/interpret.json`.
 */
object Interpretation {
    /** How long the host waits for the interpreter before the phrase counts as conversation. */
    const val INTERPRET_TIMEOUT_MS = 3_000L
    /** Upcoming titles given to the interpreter as context. */
    const val INTERPRET_UP_NEXT = 5
    /** The longest question the host will say, in code points. */
    const val ASK_MAX_CHARS = 80
    /** How long after the question the reply's phrase may arrive (inclusive). */
    const val ANSWER_MS = 10_000L
    /** How much longer the host waits for a reply before it plays the fallback. */
    const val ANSWER_GRACE_MS = 2_000L

    /** What an answer asks the host to do; null stands for conversation. */
    sealed interface Outcome
    /** Execute [text], a command text the grammar parses. */
    data class Do(val text: String) : Outcome
    /** Say [question] and wait for a reply; [fallback] is the command text to execute if none is usable. */
    data class Ask(val question: String, val fallback: String?) : Outcome

    private val kinds = Command.Kind.entries.map { it.word }.toSet()
    private val words = mapOf(
        "pause" to "pause", "resume" to "resume", "next" to "next", "previous" to "previous",
        "shuffle" to "shuffle", "volumeUp" to "volume up", "volumeDown" to "volume down",
        "nowplaying" to "what is playing", "end" to "over",
    )
    private val space = Regex("\\s+")

    /** What [answer] (the model's text) means, or null = conversation. */
    fun outcome(answer: String): Outcome? {
        val obj = runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return null
        return when (val action = obj.string("action") ?: return null) {
            "play" -> play(obj)?.let(::Do)
            "queue" -> queue(obj)?.let(::Do)
            "ask" -> {
                val fallback = play(obj)
                val question = obj.string("question")?.replace(space, " ")?.trim().orEmpty()
                if (question.isEmpty() || question.codePointCount(0, question.length) > ASK_MAX_CHARS) fallback?.let(::Do)
                else Ask(question, fallback)
            }
            else -> words[action]?.let(::Do)
        }
    }

    /** `play <kind> <query>` from the answer's `kind` and `query`, or null without a usable query. */
    private fun play(obj: JsonObject): String? {
        val query = CommandParser.normalize(obj.string("query") ?: return null)
        if (query.isEmpty()) return null
        val kind = obj.string("kind")?.takeIf { it in kinds } ?: Command.Kind.SONG.word
        return "play $kind $query"
    }

    /** `queue [next|instead] [<count>] <kind> <query>` or `… similar`, or null without a usable source. */
    private fun queue(obj: JsonObject): String? {
        val source = if (obj.string("kind") == CommandParser.SIMILAR) CommandParser.SIMILAR else play(obj)?.removePrefix("play ") ?: return null
        val where = obj.string("where")?.takeIf { it == Command.Where.NEXT.word || it == Command.Where.INSTEAD.word }
        val count = (obj["count"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..CommandParser.QUEUE_MAX_COUNT }
        return listOfNotNull("queue", where, count?.toString(), source).joinToString(" ")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
