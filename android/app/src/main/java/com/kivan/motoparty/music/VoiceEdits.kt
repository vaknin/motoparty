package com.kivan.motoparty.music

import com.kivan.motoparty.core.CommandParser
import com.kivan.motoparty.core.Interpretation
import com.kivan.motoparty.core.RepeatMode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * PROTOCOL.md "Commands", *Voice actions*: the queue arithmetic of `remove`, `move` and `jump` on
 * window positions, and the words the host says about them. Positions are resolved against the
 * [VoiceSnapshot] and executed on ids, so a queue that changed meanwhile cannot make an edit hit
 * the wrong song. Pure, so it is tested without a player.
 */
object VoiceEdits {
    /** An edit of the upcoming list: the new list and the tracks it took out (or moved). */
    data class Edit(val upcoming: List<Track>, val tracks: List<Track>, val missing: Int = 0)

    /**
     * Where window position [n] (1-based) of [snapshot] is in [upcoming] now: the occurrence of its
     * id nearest to where it was (a track can be queued twice), not one in [taken]; null when it
     * is no longer upcoming.
     */
    fun locate(upcoming: List<Track>, snapshot: VoiceSnapshot, n: Int, taken: Set<Int> = emptySet()): Int? {
        val id = snapshot.upNext.getOrNull(n - 1) ?: return null
        return upcoming.indices.filter { upcoming[it].id == id && it !in taken }.minByOrNull { abs(it - (n - 1)) }
    }

    /** `remove at`: the tracks at window positions [at] taken out; [Edit.missing] counts those already gone. */
    fun removed(upcoming: List<Track>, snapshot: VoiceSnapshot, at: List<Int>): Edit {
        val found = picks(upcoming, snapshot, at)
        return Edit(upcoming.filterIndexed { i, _ -> i !in found }, found.map { upcoming[it] }, at.size - found.size)
    }

    /**
     * `remove artist`: every upcoming track by [artist] (normalised), also past the window. A match is
     * the artist's whole words in the track's artist (YouTube's " - Topic" left out), so "moby"
     * matches "Moby" and "Moby & Gwen Stefani" but not "Mobyland".
     */
    fun removedArtist(upcoming: List<Track>, artist: String): Edit {
        val words = " ${CommandParser.normalize(artist)} "
        if (words.isBlank()) return Edit(upcoming, emptyList())
        fun by(t: Track) = " ${CommandParser.normalize(Catalog.cleanArtist(t.artist))} ".contains(words)
        return Edit(upcoming.filterNot(::by), upcoming.filter(::by))
    }

    /**
     * `move`: the tracks at window positions [at] taken out and put back as a block, in the order
     * named, so the first is upcoming position [to] (1 = next; past the end = the end).
     */
    fun moved(upcoming: List<Track>, snapshot: VoiceSnapshot, at: List<Int>, to: Int): Edit {
        val found = picks(upcoming, snapshot, at)
        val block = found.map { upcoming[it] }
        val rest = upcoming.filterIndexed { i, _ -> i !in found }
        val where = (to - 1).coerceIn(0, rest.size)
        return Edit(rest.take(where) + block + rest.drop(where), block, at.size - found.size)
    }

    /** The indexes in [upcoming] of window positions [at], in their order, each once. */
    private fun picks(upcoming: List<Track>, snapshot: VoiceSnapshot, at: List<Int>): List<Int> {
        val taken = LinkedHashSet<Int>()
        for (n in at) locate(upcoming, snapshot, n, taken)?.let { taken += it }
        return taken.toList()
    }

    // ---- what is said (PROTOCOL.md *Running a list*: one line per list) ----

    /** One part of the spoken line; [failed] parts alone make the earcon `error`. */
    data class Part(val text: String, val failed: Boolean = false)

    /** The line for [parts] (in the list's order) and whether every one is a failure; null = nothing to say. */
    fun line(parts: List<Part>): Pair<String, Boolean>? {
        if (parts.isEmpty()) return null
        return parts.joinToString(". ") { it.text.trimEnd('.') } to parts.all { it.failed }
    }

    fun removedLine(tracks: List<Track>): Part = when (tracks.size) {
        0 -> Part("Nothing to remove", failed = true)
        1 -> Part("Removed ${MusicController.nowPlayingLine(tracks[0])}")
        else -> Part("Removed ${tracks.size} songs")
    }

    /** [to] is the position asked for; [size] is the length of the upcoming list after the move. */
    fun movedLine(tracks: List<Track>, to: Int, size: Int): Part {
        if (tracks.isEmpty()) return Part("Nothing to move", failed = true)
        val where = when {
            to <= 1 -> "next"
            to + tracks.size - 1 >= size -> "the end"
            else -> "$to"
        }
        val what = tracks.singleOrNull()?.title ?: "${tracks.size} songs"
        return Part("Moved $what to $where")
    }

    fun clearedLine(count: Int): Part = if (count == 0) Part("Nothing to clear", failed = true) else Part("Cleared the queue")

    /** `undo`: [restored] tracks that were not upcoming before it came back. */
    fun undoLine(restored: Int?): Part = when (restored) {
        null -> Part("Nothing to undo", failed = true)
        0 -> Part("Undone")
        1 -> Part("Put back 1 song")
        else -> Part("Put back $restored songs")
    }

    /** `tell track` (PROTOCOL.md *Running a list*); the other `tell` lines follow. */
    fun tellTrack(current: Track?): Part =
        if (current == null) Part("Nothing playing", failed = true) else Part(MusicController.nowPlayingLine(current))

    fun tellAlbum(current: Track?): Part = when {
        current == null -> Part("Nothing playing", failed = true)
        current.album.isNullOrBlank() -> Part("Album unknown")
        else -> Part("From ${Catalog.cleanAlbum(current.album)}")
    }

    fun tellNext(upcoming: List<Track>): Part =
        upcoming.firstOrNull()?.let { Part("Next: ${MusicController.nowPlayingLine(it)}") } ?: Part("Nothing after this")

    fun tellPrevious(previous: Track?): Part =
        previous?.let { Part("Before this: ${MusicController.nowPlayingLine(it)}") } ?: Part("Nothing before this")

    /** "<n> songs left, about <m> minutes": the upcoming tracks plus what is left of the current one. */
    fun tellRemaining(current: Track?, positionMs: Long, upcoming: List<Track>): Part {
        if (upcoming.isEmpty()) return Part("Nothing after this")
        val left = upcoming.sumOf { it.durationMs.coerceAtLeast(0) } +
            (current?.durationMs?.let { (it - positionMs).coerceAtLeast(0) } ?: 0)
        val minutes = (left / 60_000.0).roundToLong().coerceAtLeast(1)
        val songs = if (upcoming.size == 1) "1 song" else "${upcoming.size} songs"
        return Part("$songs left, about $minutes minute${if (minutes == 1L) "" else "s"}")
    }

    // ---- lastVoice ----

    /** A track in the `lastVoice` summary: as in `upNext`. */
    fun short(t: Track): String = VoiceWindow.line(t)

    /** "removed Clocks – Coldplay", "removed 3: A – x, B – y, C – z": at most three named. */
    fun named(verb: String, tracks: List<Track>): String = when (tracks.size) {
        1 -> "$verb ${short(tracks[0])}"
        else -> "$verb ${tracks.size}: ${tracks.take(3).joinToString(", ", transform = ::short)}${if (tracks.size > 3) ", …" else ""}"
    }

    /**
     * The window's `lastVoice`: [summary] of the last list that changed something, done at [atMs],
     * with how long ago at [nowMs]; null when there is none or it is older than [Interpretation.UNDO_MS].
     */
    fun lastVoice(summary: String?, atMs: Long, nowMs: Long): String? {
        if (summary == null || nowMs - atMs > Interpretation.UNDO_MS) return null
        val minutes = (nowMs - atMs) / 60_000
        return "$summary (${if (minutes < 1) "just now" else "$minutes min ago"})"
    }
}

/**
 * PROTOCOL.md "Commands", *Voice undo*: one level, the upcoming list and the repeat mode from just
 * before the last voice list that changed them, for [Interpretation.UNDO_MS]. Pure; the host
 * passes its clock.
 */
class VoiceUndo {
    data class Saved(val upcoming: List<Track>, val repeat: RepeatMode, val atMs: Long)

    private var saved: Saved? = null

    /** Before a list that changes the upcoming queue or the repeat mode. */
    fun keep(upcoming: List<Track>, repeat: RepeatMode, nowMs: Long) {
        saved = Saved(upcoming, repeat, nowMs)
    }

    /** A `play` or a `jump`: a new queue or a new current track. */
    fun forget() {
        saved = null
    }

    /** What an `undo` at [nowMs] puts back, or null (nothing kept, or too old). It is then gone: an undo is not undone. */
    fun take(nowMs: Long): Saved? {
        val s = saved
        saved = null
        return s?.takeIf { nowMs - it.atMs <= Interpretation.UNDO_MS }
    }

    companion object {
        /** The upcoming list [s] puts back with [current] playing: the current track is left out of it. */
        fun restored(s: Saved, current: Track?): List<Track> = s.upcoming.filterNot { it.id == current?.id }

        /** How many of [restored] were not upcoming in [before] (by id, counting repeats). */
        fun cameBack(restored: List<Track>, before: List<Track>): Int {
            val have = before.groupingBy { it.id }.eachCount().toMutableMap()
            return restored.count { t ->
                val n = have[t.id] ?: 0
                if (n > 0) { have[t.id] = n - 1; false } else true
            }
        }
    }
}
