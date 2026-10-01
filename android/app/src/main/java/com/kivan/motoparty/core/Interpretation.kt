package com.kivan.motoparty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * PROTOCOL.md "Commands", *Interpretation* and *Voice actions*: the interpreter's answer → a list
 * of [VoiceAction]s, run by the same executor as the grammar's commands, or a clarifying question
 * (*The clarifying question*). Pure; vectors: `fixtures/interpret.json`.
 */
object Interpretation {
    /** How long the host waits for the interpreter before the phrase counts as conversation. */
    const val INTERPRET_TIMEOUT_MS = 6_000L
    /** Upcoming tracks numbered in the context window. */
    const val INTERPRET_UP_NEXT = 25
    /** Played tracks numbered in the context window (−1…−5). */
    const val INTERPRET_PLAYED = 5
    /** The most actions one answer runs. */
    const val MAX_ACTIONS = 4
    /** How long the last voice change can be undone, and `lastVoice` is shown. */
    const val UNDO_MS = 600_000L
    /** The longest question the host will say, in code points. */
    const val ASK_MAX_CHARS = 80
    /** How long after the question the reply's phrase may arrive (inclusive). */
    const val ANSWER_MS = 10_000L
    /** How much longer the host waits for a reply before it plays the fallback. */
    const val ANSWER_GRACE_MS = 2_000L

    /** What an answer asks the host to do; null stands for conversation. */
    sealed interface Outcome
    /** Run [actions] (1..[MAX_ACTIONS]), in order. */
    data class Do(val actions: List<VoiceAction>) : Outcome
    /** Say [question] and wait for a reply; [fallback] (at most one `play`) runs if none is usable. */
    data class Ask(val question: String, val fallback: List<VoiceAction>) : Outcome

    private val kinds = Command.Kind.entries.associateBy { it.word }
    private val simple: Map<String, VoiceAction> = listOf(
        VoiceAction.Clear, VoiceAction.Pause, VoiceAction.Resume, VoiceAction.Next, VoiceAction.Previous,
        VoiceAction.Shuffle, VoiceAction.End, VoiceAction.Restart, VoiceAction.VolumeUp, VoiceAction.VolumeDown,
        VoiceAction.Undo,
    ).associateBy { it.type }
    private val abouts = VoiceAction.About.entries.associateBy { it.word }
    private val space = Regex("\\s+")

    /**
     * What [answer] (the model's text) means, or null = conversation. [upNext] and [played] are the
     * sizes of the window that was sent: a position outside them drops its action.
     */
    fun outcome(answer: String, upNext: Int = INTERPRET_UP_NEXT, played: Int = INTERPRET_PLAYED): Outcome? {
        val root = runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return null
        val list = root["actions"] as? JsonArray ?: return null
        val objects = list.filterIsInstance<JsonObject>()
        // `none` next to real actions is ignored, and so is an `ask`: those run.
        val actions = objects.mapNotNull { action(it, upNext, played) }
        if (actions.isNotEmpty()) return Do(actions.take(MAX_ACTIONS))
        val ask = objects.firstOrNull { it.string("type") == "ask" } ?: return null
        val fallback = play(ask)?.takeIf { it.kind != null }
        val question = ask.string("question")?.replace(space, " ")?.trim().orEmpty()
        return if (question.isEmpty() || question.codePointCount(0, question.length) > ASK_MAX_CHARS) fallback?.let { Do(listOf(it)) }
        else Ask(question, listOfNotNull(fallback))
    }

    /** One action of the list, or null when it is unusable (or `none`, or `ask`). */
    private fun action(obj: JsonObject, upNext: Int, played: Int): VoiceAction? {
        val type = obj.string("type") ?: return null
        simple[type]?.let { return it }
        return when (type) {
            "play" -> play(obj)
            "add" -> play(obj)?.let { p ->
                val where = when (obj.string("where")) {
                    Command.Where.NEXT.word -> Command.Where.NEXT
                    Command.Where.INSTEAD.word -> Command.Where.INSTEAD
                    else -> Command.Where.END
                }
                val count = obj.int("count")?.takeIf { it in 1..CommandParser.QUEUE_MAX_COUNT }
                VoiceAction.Add(p.kind, p.query, where, count)
            }
            "remove" -> {
                val at = obj["at"] as? JsonArray
                if (!at.isNullOrEmpty()) positions(at, upNext)?.let { VoiceAction.Remove(at = it) }
                else obj.string("artist")?.let(CommandParser::normalize)?.takeIf { it.isNotEmpty() }?.let { VoiceAction.Remove(artist = it) }
            }
            "move" -> {
                val at = (obj["at"] as? JsonArray)?.let { positions(it, upNext) } ?: return null
                val to = obj.int("to")?.takeIf { it >= 1 } ?: return null
                VoiceAction.Move(at, to)
            }
            "jump" -> obj.int("at")?.takeIf { it in 1..upNext || it in -played..-1 }?.let { VoiceAction.Jump(it) }
            "seek" -> {
                val by = obj.int("by")?.takeIf { it != 0 }
                val to = obj.int("to")?.takeIf { it >= 0 }
                when {
                    by != null -> VoiceAction.Seek(by = by)
                    to != null -> VoiceAction.Seek(to = to)
                    else -> null
                }
            }
            "repeat" -> RepeatMode.of(obj.string("mode"))?.let { VoiceAction.Repeat(it) }
            "tell" -> abouts[obj.string("about")]?.let { VoiceAction.Tell(it) }
            else -> null
        }
    }

    /** Window positions 1..[upNext], duplicates once, in order; null if any is not one or there are none. */
    private fun positions(at: JsonArray, upNext: Int): List<Int>? {
        val out = LinkedHashSet<Int>()
        for (e in at) out += e.int()?.takeIf { it in 1..upNext } ?: return null
        return out.toList().ifEmpty { null }
    }

    /**
     * `play` from the answer's `kind` and `query` (also the `ask` fallback's), or null without a
     * usable query. `similar` takes no query; an unknown or absent kind is `song`.
     */
    private fun play(obj: JsonObject): VoiceAction.Play? {
        val kindWord = obj.string("kind")
        if (kindWord == CommandParser.SIMILAR) return VoiceAction.Play(null, "")
        val query = CommandParser.normalize(obj.string("query") ?: return null)
        if (query.isEmpty()) return null
        return VoiceAction.Play(kinds[kindWord] ?: Command.Kind.SONG, query)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = this[key]?.int()
    /** A JSON integer (not a string, not a fraction) that fits an Int. */
    private fun JsonElement.int(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
