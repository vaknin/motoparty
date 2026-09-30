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

    /** Whether [tracks] (an album) holds [track]: the same id, or the same title. */
    fun holds(tracks: List<Track>, track: Track): Boolean {
        val title = CommandParser.normalize(track.title)
        return tracks.any { it.id == track.id || CommandParser.normalize(it.title) == title }
    }

    /**
     * Whether a `queue album <query>` whose album [found] does not hold [current] should be
     * replaced by the album that does: when the query names the current artist, it was most
     * likely "the rest of this album" with an album the interpreter guessed wrong. The cost: a
     * rider who asks for another album of the artist playing gets the current one instead.
     */
    fun wantsCurrentAlbum(cmd: Command.Queue, found: List<Track>, current: Track?): Boolean {
        if (cmd.kind != Command.Kind.ALBUM || current == null || holds(found, current)) return false
        val artist = CommandParser.normalize(Catalog.cleanArtist(current.artist))
        return artist.isNotEmpty() && " ${CommandParser.normalize(cmd.query)} ".contains(" $artist ")
    }

    /** The spoken reply for [added] (not empty) tracks. */
    fun reply(where: Command.Where, added: List<Track>): String {
        val what = added.singleOrNull()?.let(MusicController::nowPlayingLine) ?: "${added.size} songs"
        return if (where == Command.Where.NEXT) "Next: $what" else "Added $what"
    }
}
