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

    /** The repeat button (2026-10-01): sets [MusicControl.mode], required with it. */
    const val REPEAT = "repeat"
}

/** PROTOCOL.md "Browsing": what `music.search` looks for. */
object SearchKind {
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val PLAYLISTS = "playlists"

    /** Artists (2026-10-02): a result's `ref` is a channel id, opened with [BrowseKind.ARTIST]. */
    const val ARTISTS = "artists"
}

/** PROTOCOL.md "Browsing" step 2a (2026-10-02): `music.browse` `kind`; absent = an album or playlist. */
object BrowseKind {
    const val ARTIST = "artist"
}

/** PROTOCOL.md "Browsing": where `music.enqueue` puts its tracks. */
object EnqueueMode {
    const val NOW = "now"
    const val NEXT = "next"
    const val END = "end"
}

/** PROTOCOL.md "Browsing": `music.edit` operations on the upcoming queue. */
object EditOp {
    const val JUMP = "jump"
    const val REMOVE = "remove"
    const val CLEAR = "clear"
    /** Drag to reorder (2026-10-01): needs `to`, the track's upcoming index afterwards. */
    const val MOVE = "move"
}

/** PROTOCOL.md "Browsing" step 6 (2026-10-01): a collection download from the client. */
object DownloadOp {
    /** Needs [MusicDownload.ids]. */
    const val START = "start"
    const val STOP = "stop"
}

/** PROTOCOL.md "Host-mic talk": the value of `mic` on the host's `talk.open` and in `state`. */
object Mic {
    const val HOST = "host"
}

/**
 * The closed value sets of PROTOCOL.md's message table, by type and field. A value outside its
 * set is malformed: dropped and logged, connection kept ("Control channel").
 */
internal val ENUM_FIELDS: Map<Pair<String, String>, Set<String>> = mapOf(
    ("hello" to "role") to setOf(Role.HOST, Role.CLIENT),
    ("talk.open" to "by") to setOf(Role.HOST, Role.CLIENT),
    ("talk.open" to "mic") to setOf(Mic.HOST),
    ("state" to "mic") to setOf(Mic.HOST),
    ("talk.close" to "by") to setOf(Role.HOST, Role.CLIENT),
    ("talk.close" to "reason") to
        setOf(CloseReason.TRIGGER, CloseReason.LINK, CloseReason.UNAVAILABLE),
    ("music.control" to "action") to
        setOf(ControlAction.PAUSE, ControlAction.RESUME, ControlAction.NEXT, ControlAction.PREVIOUS, ControlAction.REPEAT),
    // Unlike `state.music.repeat`, `off` is a value here: the mode to set.
    ("music.control" to "mode") to RepeatMode.entries.map { it.word }.toSet(),
    ("announce" to "earcon") to setOf(Earcon.OK, Earcon.ERROR),
    ("music.search" to "kind") to setOf(SearchKind.SONGS, SearchKind.ALBUMS, SearchKind.PLAYLISTS, SearchKind.ARTISTS),
    ("music.browse" to "kind") to setOf(BrowseKind.ARTIST),
    ("music.enqueue" to "mode") to setOf(EnqueueMode.NOW, EnqueueMode.NEXT, EnqueueMode.END),
    ("music.edit" to "op") to setOf(EditOp.JUMP, EditOp.REMOVE, EditOp.CLEAR, EditOp.MOVE),
    ("music.download" to "op") to setOf(DownloadOp.START, DownloadOp.STOP),
    // Nested: `off` is never sent (absent means off).
    ("state" to "music.repeat") to setOf(RepeatMode.TRACK.word, RepeatMode.QUEUE.word),
)

@Serializable
@SerialName("hello")
data class Hello(
    val proto: Int,
    val role: String,
    val name: String,
    val voicePort: Int? = null,
    val httpPort: Int? = null,
    /** Host only: it interprets unparsed first phrases (PROTOCOL.md "Commands", Interpretation). Absent = false. */
    val interpret: Boolean? = null,
) : Message

@Serializable
@SerialName("ping")
data class Ping(val id: Long, val t0: Long) : Message

@Serializable
@SerialName("pong")
data class Pong(val id: Long, val t0: Long, val t1: Long, val t2: Long) : Message

@Serializable
@SerialName("talk.open")
data class TalkOpen(
    val by: String,
    /**
     * [Mic.HOST] on the host's decision when the host captures both riders itself (PROTOCOL.md
     * "Host-mic talk"); absent otherwise. A client never sends it and the host ignores it there.
     */
    val mic: String? = null,
) : Message

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

/**
 * PROTOCOL.md "Music flow" step 6: track [id] starts at position 0 at [atHostTimeMs], the moment
 * the current track ends by its anchor, so the two play without a gap.
 */
@Serializable
@SerialName("music.next")
data class MusicNext(val id: String, val atHostTimeMs: Long) : Message

@Serializable
@SerialName("music.stop")
data object MusicStop : Message

@Serializable
@SerialName("music.control")
data class MusicControl(
    val action: String,
    /** [RepeatMode.word] for [ControlAction.REPEAT] (required there, `off` included); absent otherwise. */
    val mode: String? = null,
) : Message

@Serializable
@SerialName("command.text")
data class CommandText(val text: String, val lang: String) : Message

@Serializable
@SerialName("music.search")
data class MusicSearch(val id: Long, val kind: String, val query: String) : Message

@Serializable
@SerialName("music.browse")
data class MusicBrowse(
    val id: Long,
    val ref: String,
    /** [BrowseKind.ARTIST] for an artist's page (2026-10-02); absent = an album or playlist. */
    val kind: String? = null,
) : Message

@Serializable
@SerialName("music.results")
data class MusicResults(
    val id: Long,
    val items: List<ResultItem>,
    val error: String? = null,
    /**
     * An artist page's albums and singles (2026-10-02, PROTOCOL.md "Browsing" step 2a), next to
     * its top songs in [items]: one reply, so the page is one request. Absent elsewhere.
     */
    val albums: List<ResultItem>? = null,
) : Message

/**
 * A song (`ref` = track id), an album/playlist (`ref` = playlist id) or an artist (`ref` =
 * channel id, `artist` empty), PROTOCOL.md "Browsing".
 */
@Serializable
data class ResultItem(
    val ref: String,
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    val count: Int? = null,
    val art: String? = null,
)

@Serializable
@SerialName("music.enqueue")
data class MusicEnqueue(val mode: String, val tracks: List<EnqueueTrack>, val art: String? = null) : Message

@Serializable
data class EnqueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
    val art: String? = null,
)

@Serializable
@SerialName("music.edit")
data class MusicEdit(
    val op: String,
    val index: Int? = null,
    val id: String? = null,
    /** [EditOp.MOVE] only, and required there: the new upcoming index, ≥ 0. */
    val to: Int? = null,
) : Message

/**
 * PROTOCOL.md "Browsing" step 6 (2026-10-01): start or stop downloading the album or playlist
 * [ref] into the host's cache. [ids] (its songs' refs, in order) is required for [DownloadOp.START].
 */
@Serializable
@SerialName("music.download")
data class MusicDownload(val op: String, val ref: String, val ids: List<String>? = null) : Message

/**
 * PROTOCOL.md "Browsing" step 6 (2026-10-01): which tracks the host has downloaded (the song rows'
 * marks) and every collection download's progress. Sent after a client's `hello` and on any change.
 */
@Serializable
@SerialName("music.downloads")
data class MusicDownloads(val cached: List<String>, val downloads: List<DownloadItem>) : Message

@Serializable
data class DownloadItem(val ref: String, val done: Int, val total: Int, val failed: Int, val running: Boolean)

@Serializable
@SerialName("announce")
data class Announce(
    val text: String,
    val earcon: String? = null,
    /** Only `true`: [text] is a clarifying question (PROTOCOL.md "Commands", *The clarifying question*). */
    val ask: Boolean? = null,
) : Message

@Serializable
@SerialName("state")
data class State(
    val talk: Boolean,
    val music: MusicState? = null,
    /** Required on the wire, possibly empty. */
    val queue: List<QueueItem>,
    /** [Mic.HOST] while [talk] is true and the open talk is a host-mic talk; absent otherwise. */
    val mic: String? = null,
    /**
     * While a voice command's search runs (2026-10-01): the status line's text, such as
     * `Searching song "moby"`, without the ellipsis. Absent otherwise.
     */
    val busy: String? = null,
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
    val art: String? = null,
    /** [RepeatMode.wire]: `"track"` or `"queue"`; absent = off (2026-10-01). */
    val repeat: String? = null,
)

@Serializable
data class QueueItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    /** Cover image URL; the host leaves it out of every item when `state` would pass 48 KiB. */
    val art: String? = null,
)

@Serializable
@SerialName("bye")
data class Bye(val reason: String? = null) : Message

/** A syntactically valid frame whose `t` this build does not know; dispatchers ignore it. */
data class UnknownMessage(val t: String) : Message

const val PROTO_VERSION = 1
