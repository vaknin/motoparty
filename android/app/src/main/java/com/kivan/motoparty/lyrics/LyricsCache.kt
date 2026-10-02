package com.kivan.motoparty.lyrics

import androidx.compose.runtime.Immutable
import com.kivan.motoparty.music.Track
import com.kivan.motoparty.music.isValidTrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** What `GET /lyrics/<id>.json` answers (PROTOCOL.md "Tracks", Lyrics). */
sealed interface LyricsAnswer {
    class Ok(val body: ByteArray) : LyricsAnswer
    /** `404`: looked and none, an invalid id, or a track the host does not know. */
    data object NotFound : LyricsAnswer
    /** `503`: a lookup is running, or it failed (offline) and may be tried again. */
    data object Busy : LyricsAnswer
}

/** The current track's lyrics, for the Ride screen ([com.kivan.motoparty.LinkStatus.lyrics]). */
@Immutable
data class LyricsView(val id: String, val kind: Kind, val lines: List<LyricLine> = emptyList()) {
    enum class Kind { LOADING, FOUND, NOT_FOUND, OFFLINE }
}

/**
 * Lyrics by track id, on disk next to the track cache: `<id>.json` is the served body, `<id>.none`
 * says LRCLIB had nothing (its mtime is when; trusted for [negativeTtlMs]). A lookup that failed
 * (no coverage, LRCLIB down) is not written down, only not tried again within [retryMs].
 *
 * The host looks lyrics up whenever it caches a track ([request]), whatever its own toggle, so
 * they are there later without coverage. A lookup needs the title, artist and length, so every
 * track the host learns of goes through [know]; an id asked for before that waits for it.
 *
 * Thread-safe: [request], [know] and [view] only touch memory (Main), [answer] reads the disk
 * (the track server's IO threads); the disk work of a lookup runs on [scope]'s IO dispatcher.
 * [onChange] runs, on that dispatcher, whenever what [view] shows for an id may have changed.
 */
class LyricsCache(
    private val dir: File,
    private val scope: CoroutineScope,
    private val find: (Track) -> List<LyricLine>?,
    private val onChange: (id: String) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val negativeTtlMs: Long = 30L * 24 * 3600 * 1000,
    private val retryMs: Long = 20_000,
) {
    private sealed interface Settled
    private data object Found : Settled
    private class None(val atMs: Long) : Settled

    private val lock = Any()
    private val settled = HashMap<String, Settled>()
    private val running = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    /** Asked for before its title and artist were known. */
    private val waiting = HashSet<String>()
    private val known = object : LinkedHashMap<String, Track>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>) = size > MAX_KNOWN
    }
    /** Decoded lines of the last few tracks [view] was asked for. */
    private val decoded = object : LinkedHashMap<String, List<LyricLine>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>) = size > MAX_DECODED
    }
    private val decoding = HashSet<String>()

    init {
        dir.mkdirs()
    }

    private fun bodyFile(id: String) = File(dir, "$id.json")
    private fun noneFile(id: String) = File(dir, "$id.none")

    /** Title, artist and length of these tracks, for their lookups. An id that waited for it is looked up now. */
    fun know(tracks: Collection<Track>) {
        val now = synchronized(lock) {
            tracks.filter { isValidTrackId(it.id) }.mapNotNull { t ->
                known[t.id] = t
                t.id.takeIf { waiting.remove(it) }
            }
        }
        now.forEach(::request)
    }

    /** Look [id] up unless it is settled, running, failed within [retryMs] or waiting for its track. */
    fun request(id: String) {
        if (!isValidTrackId(id)) return
        synchronized(lock) { if (!startable(id)) return }
        start(id)
    }

    /** Under [lock]. */
    private fun startable(id: String): Boolean {
        val s = settled[id]
        if (s is Found || (s is None && fresh(s))) return false
        if (id in running || id in waiting) return false
        val failed = failedAt[id]
        return failed == null || nowMs() - failed >= retryMs
    }

    private fun fresh(n: None) = nowMs() - n.atMs < negativeTtlMs

    private fun start(id: String) {
        synchronized(lock) {
            if (!running.add(id)) return
        }
        scope.launch(Dispatchers.IO) {
            try {
                lookup(id)
            } finally {
                synchronized(lock) { running.remove(id) }
                onChange(id)
            }
        }
    }

    /** The disk first; then LRCLIB, if the track is known. */
    private fun lookup(id: String) {
        if (fromDisk(id) != null) return
        val track = synchronized(lock) { known[id] } ?: run {
            synchronized(lock) { waiting += id }
            return
        }
        val lines = try {
            find(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { failedAt[id] = nowMs() }
            log("lyrics $id: lookup failed: $e")
            return
        }
        synchronized(lock) { failedAt.remove(id) }
        if (lines == null) {
            noneFile(id).writeText("")
            synchronized(lock) { settled[id] = None(nowMs()) }
            log("lyrics $id: none for “${track.artist} – ${track.title}”")
        } else {
            val tmp = File(dir, "$id.json.part")
            tmp.writeBytes(LyricsBody(id, lines = lines).encode())
            tmp.renameTo(bodyFile(id))
            noneFile(id).delete()
            synchronized(lock) {
                settled[id] = Found
                decoded[id] = lines
            }
            log("lyrics $id: ${lines.size} lines")
        }
    }

    /** What the disk says, settled into memory; null when it says nothing (or only something stale). */
    private fun fromDisk(id: String): Settled? {
        val s = when {
            bodyFile(id).isFile -> Found
            noneFile(id).isFile -> None(noneFile(id).lastModified()).takeIf(::fresh)
            else -> null
        } ?: return null
        synchronized(lock) { settled[id] = s }
        return s
    }

    /** The `/lyrics/<id>.json` answer, starting a lookup where one may start. Blocking (disk). */
    fun answer(id: String): LyricsAnswer {
        if (!isValidTrackId(id)) return LyricsAnswer.NotFound
        val s = synchronized(lock) { settled[id] } ?: fromDisk(id)
        when {
            s is Found -> {
                val body = runCatching { bodyFile(id).readBytes() }.getOrNull()
                if (body != null) return LyricsAnswer.Ok(body)
                // Removed under us (the system cleared the cache dir): look again.
                synchronized(lock) { settled.remove(id) }
            }
            s is None && fresh(s) -> return LyricsAnswer.NotFound
        }
        synchronized(lock) {
            if (id in running) return LyricsAnswer.Busy
            if (id !in known) return LyricsAnswer.NotFound
            if (!startable(id)) return LyricsAnswer.Busy // failed a moment ago: still offline
        }
        start(id)
        return LyricsAnswer.Busy
    }

    /** What the Ride screen shows for [id]. Memory only; decodes on [scope] and calls [onChange] when it has. */
    fun view(id: String): LyricsView {
        synchronized(lock) {
            when (val s = settled[id]) {
                is Found -> {
                    decoded[id]?.let { return LyricsView(id, LyricsView.Kind.FOUND, it) }
                    if (decoding.add(id)) decode(id)
                    return LyricsView(id, LyricsView.Kind.LOADING)
                }
                is None -> if (fresh(s)) return LyricsView(id, LyricsView.Kind.NOT_FOUND)
                null -> Unit
            }
            return when {
                id in running -> LyricsView(id, LyricsView.Kind.LOADING)
                id in failedAt -> LyricsView(id, LyricsView.Kind.OFFLINE)
                // Nothing to look it up by.
                id in waiting -> LyricsView(id, LyricsView.Kind.NOT_FOUND)
                else -> LyricsView(id, LyricsView.Kind.LOADING)
            }
        }
    }

    private fun decode(id: String) {
        scope.launch(Dispatchers.IO) {
            val lines = try {
                LyricsBody.decode(bodyFile(id).readText()).lines
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("lyrics $id: unreadable ($e), looking again")
                bodyFile(id).delete()
                null
            }
            synchronized(lock) {
                decoding.remove(id)
                if (lines != null) decoded[id] = lines else settled.remove(id)
            }
            if (lines == null) request(id)
            onChange(id)
        }
    }

    companion object {
        private const val MAX_KNOWN = 2000
        private const val MAX_DECODED = 4
    }
}
