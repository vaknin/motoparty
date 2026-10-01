package com.kivan.motoparty.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PROTOCOL.md "Commands", *Voice actions* (2026-10-01): the one currency of the grammar, the
 * interpreter and the voice undo. Whatever was said becomes a list of these, which the host's one
 * executor runs. Positions ([Remove.at], [Move.at], [Jump.at]) are the numbers of the context
 * window the interpreter was sent (1 = the next track, −1 = the one before this), resolved against
 * its snapshot by the host. Pure; [canonical] is the form of `fixtures/interpret.json`.
 */
sealed interface VoiceAction {
    /** Replace the queue. [kind] null = `similar` (music like the current track), and [query] is then empty. */
    data class Play(val kind: Command.Kind?, val query: String) : VoiceAction
    /** The grammar's `queue …` ("Queueing by voice"); [kind] null = `similar`; [count] 1..50 or null. */
    data class Add(val kind: Command.Kind?, val query: String, val where: Command.Where, val count: Int?) : VoiceAction
    /** Drop upcoming tracks: the window positions [at], or (when [at] is empty) every one by [artist] (normalised). */
    data class Remove(val at: List<Int> = emptyList(), val artist: String? = null) : VoiceAction
    /** Take the tracks at [at] out and put them back, in their order, so the first is at [to] (1 = next). */
    data class Move(val at: List<Int>, val to: Int) : VoiceAction
    data object Clear : VoiceAction
    /** Play the upcoming track at [at] now, or (negative) the played one: −1 = the track before this. */
    data class Jump(val at: Int) : VoiceAction
    data object Pause : VoiceAction
    data object Resume : VoiceAction
    data object Next : VoiceAction
    data object Previous : VoiceAction
    data object Shuffle : VoiceAction
    /** Close the talk and change nothing else. */
    data object End : VoiceAction
    data object Restart : VoiceAction
    /** Move in the current track: by [by] seconds (±), or to [to] seconds; exactly one is set. */
    data class Seek(val by: Int? = null, val to: Int? = null) : VoiceAction
    data class Repeat(val mode: RepeatMode) : VoiceAction
    data object VolumeUp : VoiceAction
    data object VolumeDown : VoiceAction
    /** Say a fact the host composes from its own data. */
    data class Tell(val about: About) : VoiceAction
    data object Undo : VoiceAction

    enum class About(val word: String) { TRACK("track"), ALBUM("album"), NEXT("next"), REMAINING("remaining"), PREVIOUS("previous") }

    /** The answer's `type` of this action. */
    val type: String
        get() = when (this) {
            is Play -> "play"
            is Add -> "add"
            is Remove -> "remove"
            is Move -> "move"
            Clear -> "clear"
            is Jump -> "jump"
            Pause -> "pause"
            Resume -> "resume"
            Next -> "next"
            Previous -> "previous"
            Shuffle -> "shuffle"
            End -> "end"
            Restart -> "restart"
            is Seek -> "seek"
            is Repeat -> "repeat"
            VolumeUp -> "volumeUp"
            VolumeDown -> "volumeDown"
            is Tell -> "tell"
            Undo -> "undo"
        }

    /** `play` and `add`: the actions that search before they can apply. */
    val searches: Boolean get() = this is Play || this is Add

    /** Volume is local to the phone that heard it (PROTOCOL.md "Commands"). */
    val isVolume: Boolean get() = this == VolumeUp || this == VolumeDown

    /** Changes the upcoming queue or the repeat mode: what a voice undo puts back. */
    val undoable: Boolean
        get() = this is Add || this is Remove || this is Move || this == Clear || this == Shuffle || this is Repeat

    /** A new queue or a new current track: the undo is forgotten. */
    val replacesMusic: Boolean get() = this is Play || this is Jump

    /** The canonical JSON of `fixtures/interpret.json`, for the tests and the log lines. */
    fun canonical(): JsonObject = buildJsonObject {
        put("type", type)
        when (val a = this@VoiceAction) {
            is Play -> {
                put("kind", a.kind?.word ?: CommandParser.SIMILAR)
                if (a.kind != null) put("query", a.query)
            }
            is Add -> {
                put("kind", a.kind?.word ?: CommandParser.SIMILAR)
                if (a.kind != null) put("query", a.query)
                put("where", a.where.word ?: "end")
                a.count?.let { put("count", it) }
            }
            is Remove -> if (a.at.isNotEmpty()) put("at", JsonArray(a.at.map(::JsonPrimitive))) else put("artist", a.artist)
            is Move -> {
                put("at", JsonArray(a.at.map(::JsonPrimitive)))
                put("to", a.to)
            }
            is Jump -> put("at", a.at)
            is Seek -> if (a.by != null) put("by", a.by) else put("to", a.to)
            is Repeat -> put("mode", a.mode.word)
            is Tell -> put("about", a.about.word)
            else -> Unit
        }
    }

    companion object {
        /** A list for a log line: the canonical objects, comma-separated. */
        fun describe(actions: List<VoiceAction>): String = actions.joinToString(", ") { it.canonical().toString() }
    }
}

/** `state.music.repeat` and the `repeat` action's `mode` (PROTOCOL.md, 2026-10-01). */
enum class RepeatMode(val word: String) {
    OFF("off"), TRACK("track"), QUEUE("queue");

    /** The touch toggle's order: off → queue → track → off. */
    fun toggled(): RepeatMode = when (this) {
        OFF -> QUEUE
        QUEUE -> TRACK
        TRACK -> OFF
    }

    /** On the wire: absent for [OFF], which the host never sends. */
    val wire: String? get() = word.takeIf { this != OFF }

    companion object {
        fun of(word: String?): RepeatMode? = entries.firstOrNull { it.word == word }
    }
}

/**
 * The grammar's command as voice actions (PROTOCOL.md "Commands", *Voice actions*): one to one;
 * `what's playing` is `tell track`, and an unparsed phrase is the empty list.
 */
fun Command.toActions(): List<VoiceAction> = when (this) {
    is Command.Play -> listOf(VoiceAction.Play(kind, query))
    is Command.Queue -> listOf(VoiceAction.Add(kind, query, where, count))
    Command.Pause -> listOf(VoiceAction.Pause)
    Command.Resume -> listOf(VoiceAction.Resume)
    Command.Next -> listOf(VoiceAction.Next)
    Command.Previous -> listOf(VoiceAction.Previous)
    Command.VolumeUp -> listOf(VoiceAction.VolumeUp)
    Command.VolumeDown -> listOf(VoiceAction.VolumeDown)
    Command.End -> listOf(VoiceAction.End)
    Command.NowPlaying -> listOf(VoiceAction.Tell(VoiceAction.About.TRACK))
    Command.Shuffle -> listOf(VoiceAction.Shuffle)
    Command.Unknown -> emptyList()
}
