package com.kivan.motoparty.music

import com.kivan.motoparty.core.Command
import com.kivan.motoparty.core.CommandParser

/**
 * PROTOCOL.md "Commands", *Queueing by voice*: which of the tracks a `queue …` command found
 * are added, and what is said about it. Pure, so it is tested without a catalog or a player.
 */
object VoiceQueue {
    /** How many tracks a `queue similar` without a count adds. */
    const val QUEUE_SIMILAR = 20

    /**
     * The tracks of [found] to add for [cmd], with [current] loaded and [upcoming] behind it:
     * of an album that holds the current track only what comes after it; nothing that is
     * current or (unless the queue is replaced) already upcoming; at most the command's count.
     */
    fun pick(cmd: Command.Queue, found: List<Track>, current: Track?, upcoming: List<Track>): List<Track> {
        var list = found
        if (cmd.kind == Command.Kind.ALBUM && current != null) {
            val title = CommandParser.normalize(current.title)
            val at = found.indexOfFirst { it.id == current.id }.takeIf { it >= 0 }
                ?: found.indexOfFirst { CommandParser.normalize(it.title) == title }
            if (at >= 0) list = found.drop(at + 1)
        }
        val have = buildSet {
            current?.let { add(it.id) }
            if (cmd.where != Command.Where.INSTEAD) upcoming.forEach { add(it.id) }
        }
        val limit = cmd.count ?: if (cmd.kind == null) QUEUE_SIMILAR else Int.MAX_VALUE
        return list.filter { it.id !in have }.distinctBy { it.id }.take(limit)
    }

    /** The spoken reply for [added] (not empty) tracks. */
    fun reply(where: Command.Where, added: List<Track>): String {
        val what = added.singleOrNull()?.let(MusicController::nowPlayingLine) ?: "${added.size} songs"
        return if (where == Command.Where.NEXT) "Next: $what" else "Added $what"
    }
}
