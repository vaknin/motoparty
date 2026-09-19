package com.kivan.motoparty.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Control-channel messages, PROTOCOL.md "Control channel". Pure Kotlin (JVM-testable). */
@Serializable
sealed interface Message

object Role {
    const val HOST = "host"
    const val CLIENT = "client"
}

object CloseReason {
    const val TRIGGER = "trigger"
    const val SILENCE = "silence"
    const val LINK = "link"

    /**
     * PROTOCOL.md "Talk flow" step 1: the sender cannot open its microphone (a cellular call, a
     * missing permission, a failed route). Talk is never *declined* — only unavailable.
     */
    const val UNAVAILABLE = "unavailable"
}

object Earcon {
    const val OK = "ok"
    const val ERROR = "error"
}

/** PROTOCOL.md `music.control`. Volume is local and is never sent (see "Commands"). */
object ControlAction {
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
}

/**
 * The closed value sets of PROTOCOL.md's message table, by type and field. A value outside its
 * set is malformed: dropped and logged, connection kept ("Control channel").
 */
internal val ENUM_FIELDS: Map<Pair<String, String>, Set<String>> = mapOf(
    ("hello" to "role") to setOf(Role.HOST, Role.CLIENT),
    ("talk.open" to "by") to setOf(Role.HOST, Role.CLIENT),
    ("talk.close" to "by") to setOf(Role.HOST, Role.CLIENT),
    ("talk.close" to "reason") to
        setOf(CloseReason.TRIGGER, CloseReason.SILENCE, CloseReason.LINK, CloseReason.UNAVAILABLE),
    ("music.control" to "action") to
        setOf(ControlAction.PAUSE, ControlAction.RESUME, ControlAction.NEXT, ControlAction.PREVIOUS),
    ("announce" to "earcon") to setOf(Earcon.OK, Earcon.ERROR),
)

@Serializable
@SerialName("hello")
data class Hello(
    val proto: Int,
    val role: String,
    val name: String,
    val voicePort: Int? = null,
    val httpPort: Int? = null,
) : Message

@Serializable
@SerialName("ping")
data class Ping(val id: Long, val t0: Long) : Message

@Serializable
@SerialName("pong")
data class Pong(val id: Long, val t0: Long, val t1: Long, val t2: Long) : Message

@Serializable
@SerialName("talk.open")
data class TalkOpen(val by: String) : Message

@Serializable
@SerialName("talk.close")
data class TalkClose(val by: String, val reason: String) : Message

@Serializable
@SerialName("music.load")
data class MusicLoad(
    val id: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
) : Message

@Serializable
@SerialName("music.ready")
data class MusicReady(val id: String) : Message

@Serializable
@SerialName("music.error")
data class MusicError(val id: String, val message: String) : Message

@Serializable
@SerialName("music.play")
data class MusicPlay(val id: String, val positionMs: Long, val atHostTimeMs: Long) : Message

@Serializable
@SerialName("music.pause")
data class MusicPause(val id: String, val positionMs: Long) : Message

@Serializable
@SerialName("music.stop")
data object MusicStop : Message

@Serializable
@SerialName("music.control")
data class MusicControl(val action: String) : Message

@Serializable
@SerialName("command.text")
data class CommandText(val text: String, val lang: String) : Message

@Serializable
@SerialName("announce")
data class Announce(val text: String, val earcon: String? = null) : Message

@Serializable
@SerialName("state")
data class State(
    val talk: Boolean,
    val music: MusicState? = null,
    /** Required on the wire, possibly empty. */
    val queue: List<QueueItem>,
) : Message

@Serializable
data class MusicState(
    val id: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val atHostTimeMs: Long,
    val durationMs: Long,
)

@Serializable
data class QueueItem(val id: String, val title: String, val artist: String)

@Serializable
@SerialName("bye")
data class Bye(val reason: String? = null) : Message

/** A syntactically valid frame whose `t` this build does not know; dispatchers ignore it. */
data class UnknownMessage(val t: String) : Message

const val PROTO_VERSION = 1
