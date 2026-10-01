package com.kivan.motoparty.core

/**
 * The "Main was busy" instrument. Every control message is stamped with its arrival time on the
 * reader thread (`ControlServer.Event.Received.atMs`); the host compares that with the moment it
 * takes the event off the channel and logs one line when the wait was long enough to matter.
 *
 * Pure, so the line a bench parser greps for is pinned by a test (like [TalkStats]). Nothing is
 * allocated below the threshold: [lineIfLate] returns null and the common case does one
 * subtraction and one comparison per message.
 *
 * Background: the 2026-09-20 AirPods bench (`tools/bench/results/2026-09-20-d1-talk-4`) lost the
 * talk re-open collapse because Main sat ~0.9 s inside a binder call to the audio service while
 * the audio thread held it; nothing in the logs said so, it had to be reconstructed from
 * timestamps. This line says it directly.
 */
object MainLag {
    /** Anything below this is normal scheduling noise on a busy Main. */
    const val THRESHOLD_MS = 100L

    fun line(type: String, waitedMs: Long): String = "control message $type waited $waitedMs ms for Main"

    fun lineIfLate(type: String, waitedMs: Long): String? =
        if (waitedMs >= THRESHOLD_MS) line(type, waitedMs) else null
}

/**
 * The wire `t` of a message, for logs. Pinned to the `@SerialName` annotations by
 * `MainLagTest.everyWireTypeMatchesItsSerialName`.
 */
val Message.wireType: String
    get() = when (this) {
        is Hello -> "hello"
        is Ping -> "ping"
        is Pong -> "pong"
        is TalkOpen -> "talk.open"
        is TalkClose -> "talk.close"
        is MusicLoad -> "music.load"
        is MusicReady -> "music.ready"
        is MusicError -> "music.error"
        is MusicPlay -> "music.play"
        is MusicPause -> "music.pause"
        is MusicNext -> "music.next"
        is MusicStop -> "music.stop"
        is MusicControl -> "music.control"
        is CommandText -> "command.text"
        is MusicSearch -> "music.search"
        is MusicBrowse -> "music.browse"
        is MusicResults -> "music.results"
        is MusicEnqueue -> "music.enqueue"
        is MusicEdit -> "music.edit"
        is MusicDownload -> "music.download"
        is MusicDownloads -> "music.downloads"
        is Announce -> "announce"
        is State -> "state"
        is Bye -> "bye"
        is UnknownMessage -> t
    }
