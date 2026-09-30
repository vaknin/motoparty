package com.kivan.motoparty.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.BitSet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Whose download goes first. A higher one pauses every lower one, which keeps its bytes. */
enum class DownloadPriority {
    /** An album/playlist **Download** button. */
    COLLECTION,
    /** Tracks further ahead in the queue. */
    PREFETCH,
    /** The track after the playing one. */
    NEXT,
    /** The track that was tapped: nothing else downloads while it does. */
    CURRENT,
}

/**
 * Downloaded tracks in `<dir>/<id>.m4a`, LRU-evicted (touched on use) above [maxBytes], never the
 * [protect]ed ones. A WebM source is handed to [remux] and only the MP4 it writes is kept, so
 * every cached file is audio-only MP4. Pure JVM (the remuxer is injected).
 *
 * **One download at a time**, the one with the highest [DownloadPriority] (among `CURRENT` ones
 * the newest, else the oldest), fetched as [parallel] ranges at once. A download that loses its
 * turn is stopped in mid-chunk and goes on from its `.part` when it is its turn again. Requests
 * for the same id share one download, at the highest priority asked; when the last of them is
 * cancelled, or on [cancel]/[retain], the download stops and its `.part` stays for the next
 * [ensure] of that track.
 *
 * A chunk that fails (a signal drop, a 5xx) is asked for again after [retryDelaysMs], at the same
 * offset; a stream URL that stopped working (403/410) is resolved again and the download goes on
 * where it was. When a download fails for good its `.part` stays too, and is continued if the
 * file is still the same size. Stream URLs are remembered for [resolveTtlMs] ([preResolve] looks
 * them up ahead of a tap).
 *
 * [ids] and [sizeBytes] are kept in memory (safe on Main); the directory is read once, on
 * [scope]'s IO dispatcher, and [onChange] runs when that is done.
 */
class TrackCache(
    private val dir: File,
    private val http: OkHttpClient,
    private val resolve: suspend (id: String) -> Source,
    private val scope: CoroutineScope,
    private val maxBytes: Long = 1L shl 30,
    private val remux: (webm: File, mp4: File) -> Unit = { _, _ -> throw IOException("no remuxer") },
    /** The set of cached tracks changed (a download finished, the start-up scan). Any thread. */
    private val onChange: () -> Unit = {},
    /** The waits before asking for a failed chunk again; one more failure after the last gives up. */
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000),
    private val chunkBytes: Long = CHUNK,
    private val log: (String) -> Unit = {},
    /** Range requests of one download in flight at once. */
    private val parallel: Int = 4,
    /** How long a resolved stream URL is used again (googlevideo URLs live ~6 h). */
    private val resolveTtlMs: Long = 3_600_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Where a track's audio is downloaded from; [webm] needs remuxing to MP4. */
    data class Source(val url: String, val webm: Boolean)

    /** One download, shared by everyone who asked for [id]. Fields are guarded by [lock]. */
    private class Entry(val id: String, var priority: DownloadPriority, var seq: Long) {
        val result = CompletableDeferred<File>()
        lateinit var job: Job
        var waiters = 0
        var cancelled = false
        /** The running fetch; cancelling it pauses the download. */
        var round: Job? = null
        /** Completed when it may be this download's turn. */
        var turn: CompletableDeferred<Unit>? = null
    }

    /** What a `.part` holds: guarded by itself. */
    private class Progress {
        var total = -1L
        val done = BitSet()
        /** Bumped when the file is started over, so a chunk of the old file is not written. */
        var generation = 0
    }

    private class Item(val size: Long, var lastUsed: Long)

    private class Resolved(val source: CompletableDeferred<Source>, val at: Long)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var seq = 0L
    /** Tracks with a `.part`, oldest first. */
    private val partial = LinkedHashMap<String, Progress>()
    private val index = HashMap<String, Item>()
    private val resolved = LinkedHashMap<String, Resolved>()
    private val preResolving = Semaphore(PRE_RESOLVE_PARALLEL)

    @Volatile private var idSet: Set<String> = emptySet()
    @Volatile private var totalBytes = 0L
    @Volatile private var protectedIds: Set<String> = emptySet()

    private val scanned: Job

    init {
        dir.mkdirs()
        scanned = scope.launch(Dispatchers.IO) {
            val found = HashMap<String, Item>()
            dir.listFiles()?.forEach { f ->
                when {
                    f.name.endsWith(".part") -> f.delete()
                    f.name.endsWith(".m4a") && f.length() > 0 ->
                        found[f.name.removeSuffix(".m4a")] = Item(f.length(), f.lastModified())
                }
            }
            synchronized(lock) {
                found.forEach { (id, item) -> index.putIfAbsent(id, item) }
                publish()
            }
            onChange()
        }
    }

    /** The start-up directory scan is done ([ids] and [sizeBytes] are complete). */
    internal suspend fun awaitScan() = scanned.join()

    /** The complete file if it is cached, else null. Never returns a partial download. */
    fun cached(id: String): File? {
        if (!isValidTrackId(id)) return null
        return File(dir, "$id.m4a").takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * The file of [id], downloaded first if need be. Cancelling the caller stops the download
     * when nobody else waits for it (the bytes are kept). Throws what the download failed with,
     * or [CancellationException] after [cancel]/[retain].
     */
    suspend fun ensure(id: String, priority: DownloadPriority = DownloadPriority.CURRENT): File {
        while (true) {
            cached(id)?.let { touch(id, it); return it }
            var created = false
            val e = synchronized(lock) {
                // Checked again under the lock a finishing download takes: never two of one track.
                if (cached(id) != null) return@synchronized null
                val cur = entries[id]
                if (cur != null && cur.cancelled) return@synchronized cur
                val e = cur ?: Entry(id, priority, ++seq).also {
                    entries[id] = it
                    it.job = scope.launch(Dispatchers.IO, CoroutineStart.LAZY) { run(it) }
                    it.job.invokeOnCompletion { _ -> finish(it, null) }
                    created = true
                }
                if (priority > e.priority) e.priority = priority
                // Asked for as the current track: the newest of those goes first.
                if (priority == DownloadPriority.CURRENT) e.seq = ++seq
                e.waiters++
                reschedule()
                e
            } ?: continue
            if (e.cancelled) {
                e.job.join()
                continue
            }
            if (created) e.job.start()
            try {
                return e.result.await()
            } finally {
                synchronized(lock) {
                    if (--e.waiters == 0 && !e.result.isCompleted) stop(e)
                }
            }
        }
    }

    fun isDownloading(id: String) = synchronized(lock) { entries[id]?.cancelled == false }

    /** Stop downloading [id], keeping its bytes. Whoever waits in [ensure] is cancelled. */
    fun cancel(id: String) {
        synchronized(lock) { entries[id]?.let(::stop) }
    }

    /**
     * Stop every download that is not one of [ids], keeping the bytes. Album/playlist
     * ([DownloadPriority.COLLECTION]) downloads are left alone unless [collections].
     */
    fun retain(ids: Collection<String>, collections: Boolean = false) {
        val keep = ids.toSet()
        synchronized(lock) {
            entries.values.filter {
                it.id !in keep && (collections || it.priority != DownloadPriority.COLLECTION)
            }.forEach(::stop)
        }
    }

    /** Tracks eviction must leave alone (the playing one, the announced next). Replaces the last set. */
    fun protect(ids: Collection<String>) {
        protectedIds = ids.toSet()
    }

    /**
     * Look up the stream URLs of [ids] now ([PRE_RESOLVE_PARALLEL] at a time: a lookup takes
     * 1.5-2 s, and a tap comes within seconds of the results), so a later [ensure] starts with
     * its first byte range. Cached tracks and fresh lookups are skipped; failures are dropped.
     */
    fun preResolve(ids: Collection<String>) {
        val wanted = ids.filter(::isValidTrackId).distinct().take(MAX_RESOLVED)
        if (wanted.isEmpty()) return
        for (id in wanted) {
            scope.launch(Dispatchers.IO) {
                preResolving.withPermit {
                    if (cached(id) != null || synchronized(lock) { fresh(id) } != null) return@withPermit
                    try {
                        source(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("pre-resolve $id failed: $e")
                    }
                }
            }
        }
    }

    /** The ids of every complete file in the cache. In memory. */
    fun ids(): Set<String> = idSet

    /** In memory. */
    fun sizeBytes(): Long = totalBytes

    // ---- the scheduler (under [lock]) ----

    private fun winner(): Entry? {
        var best: Entry? = null
        for (e in entries.values) {
            if (e.cancelled) continue
            val b = best
            if (b == null || e.priority > b.priority ||
                (e.priority == b.priority && (e.seq > b.seq) == (e.priority == DownloadPriority.CURRENT))
            ) best = e
        }
        return best
    }

    /** Pause whatever is running out of turn and wake the download whose turn it is. */
    private fun reschedule() {
        val w = winner()
        for (e in entries.values) if (e !== w) e.round?.cancel()
        w?.turn?.complete(Unit)
    }

    private fun stop(e: Entry) {
        if (e.cancelled) return
        e.cancelled = true
        e.job.cancel()
        log("download ${e.id}: cancelled")
        reschedule()
    }

    /** [e] is over: out of the table first, then its waiters hear about it. */
    private fun finish(e: Entry, file: File?, error: Throwable? = null) {
        synchronized(lock) {
            if (entries.remove(e.id, e)) {
                // Bound the bytes left behind by downloads nobody came back for.
                val spare = partial.keys.filter { it !in entries }
                spare.take((partial.size - MAX_PARTS).coerceAtLeast(0)).forEach {
                    partial.remove(it)
                    File(dir, "$it.m4a.part").delete()
                }
                reschedule()
            }
        }
        when {
            file != null -> e.result.complete(file)
            error != null && error !is CancellationException -> e.result.completeExceptionally(error)
            else -> e.result.cancel()
        }
    }

    private suspend fun run(e: Entry) {
        try {
            val file = download(e)
            finish(e, file)
            onChange()
        } catch (t: Throwable) {
            if (t !is CancellationException) synchronized(lock) { resolved.remove(e.id) }
            finish(e, null, t)
        }
    }

    // ---- stream URLs ----

    private fun fresh(id: String): Resolved? =
        resolved[id]?.takeIf { nowMs() - it.at < resolveTtlMs && !it.source.isCancelled }

    /** The stream of [id]: a lookup under way or not older than [resolveTtlMs], else a new one. */
    private suspend fun source(id: String): Source {
        var mine = false
        val r = synchronized(lock) {
            fresh(id) ?: Resolved(CompletableDeferred(), nowMs()).also {
                resolved.remove(id)
                resolved[id] = it
                while (resolved.size > MAX_RESOLVED) resolved.remove(resolved.keys.first())
                mine = true
            }
        }
        if (mine) {
            // In [scope], so a caller that is paused or cancelled does not lose the lookup.
            scope.launch(Dispatchers.IO) {
                try {
                    r.source.complete(resolve(id))
                } catch (t: Throwable) {
                    synchronized(lock) { if (resolved[id] === r) resolved.remove(id) }
                    r.source.completeExceptionally(t)
                }
            }.invokeOnCompletion { if (!r.source.isCompleted) r.source.cancel() }
        }
        return r.source.await()
    }

    // ---- one download ----

    /** The state of one download across its rounds. */
    private inner class Fetch(val id: String, val p: Progress, val raf: RandomAccessFile) {
        val file: FileChannel = raf.channel
        @Volatile var source: Source? = null
        var resolves = 0
        val renewing = Mutex()
        /** Chunks being fetched in this round; guarded by [p]. */
        val claimed = BitSet()

        fun chunks(total: Long) = ((total + chunkBytes - 1) / chunkBytes).toInt()
        fun complete() = synchronized(p) { p.total >= 0 && p.done.cardinality() >= chunks(p.total) }
        fun known() = synchronized(p) { p.total >= 0 }
        fun left() = synchronized(p) { chunks(p.total) - p.done.cardinality() }

        /** The next chunk nobody has or is fetching; null when there is none (or the size is unknown). */
        fun claim(): Int? = synchronized(p) {
            if (p.total < 0) return null
            var i = 0
            val n = chunks(p.total)
            while (i < n && (p.done[i] || claimed[i])) i++
            if (i < n) i.also { claimed.set(it) } else null
        }

        fun startOver() {
            file.truncate(0)
            p.done.clear()
            claimed.clear()
            p.total = -1
            p.generation++
        }
    }

    private suspend fun download(e: Entry): File {
        scanned.join()
        val id = e.id
        val part = File(dir, "$id.m4a.part")
        val target = File(dir, "$id.m4a")
        val p = synchronized(lock) { partial.getOrPut(id) { Progress() } }
        val webm: Boolean
        RandomAccessFile(part, "rw").use { raf ->
            // Continue a download that stopped earlier, if its bytes are still there.
            synchronized(p) {
                if (p.total > 0 && raf.length() == p.total && !p.done.isEmpty) {
                    val have = minOf(p.done.cardinality() * chunkBytes, p.total)
                    log("download $id: continuing, $have of ${p.total} there")
                } else {
                    raf.setLength(0)
                    p.done.clear()
                    p.total = -1
                    p.generation++
                }
            }
            val f = Fetch(id, p, raf)
            var paused = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val parent = currentCoroutineContext().job
                var turn: CompletableDeferred<Unit>? = null
                val round = synchronized(lock) {
                    if (winner() === e) Job(parent).also { e.round = it }
                    else null.also { turn = CompletableDeferred<Unit>().also { e.turn = it } }
                }
                if (round == null) {
                    if (!paused) log("download $id: waits for a ${winnerPriority()} download")
                    paused = true
                    turn!!.await()
                    continue
                }
                try {
                    // googlevideo throttles long single responses; ~1 MiB range requests stay fast.
                    withContext(round) { fetchAll(f) }
                    break
                } catch (c: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    paused = false // say so again: it was running
                } finally {
                    synchronized(lock) { e.round = null }
                    round.cancel()
                }
            }
            webm = f.source!!.webm
        }
        synchronized(lock) { partial.remove(id) }
        if (webm) {
            val mp4 = File(dir, "$id.m4a.remux.part")
            try {
                val t0 = System.nanoTime()
                remux(part, mp4)
                log("download $id: remuxed in ${(System.nanoTime() - t0) / 1_000_000} ms")
                if (!mp4.renameTo(target)) throw IOException("rename failed for $id")
            } finally {
                part.delete()
                mp4.delete()
            }
        } else if (!part.renameTo(target)) {
            throw IOException("rename failed for $id")
        }
        synchronized(lock) {
            index[id] = Item(target.length(), nowMs())
            evict(keep = id)
            publish()
        }
        return target
    }

    private fun winnerPriority() = synchronized(lock) { winner()?.priority }

    private suspend fun fetchAll(f: Fetch) {
        if (f.source == null) f.source = source(f.id)
        synchronized(f.p) { f.claimed.clear() }
        while (!f.complete()) {
            if (!f.known()) {
                one(f, 0) // the first chunk tells the size
            } else coroutineScope {
                repeat(minOf(parallel, f.left()).coerceAtLeast(1)) {
                    launch { while (true) one(f, f.claim() ?: break) }
                }
            }
        }
    }

    /** Fetch chunk [index] into the file, however many tries that takes. */
    private suspend fun one(f: Fetch, index: Int) {
        val id = f.id
        val offset = index * chunkBytes
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val (generation, total) = synchronized(f.p) { f.p.generation to f.p.total }
            val end = if (total < 0) offset + chunkBytes - 1 else minOf(offset + chunkBytes, total) - 1
            val source = f.source!!
            val chunk = try {
                fetch(id, source.url, offset, end)
            } catch (e: IOException) {
                val status = (e as? HttpStatusException)?.code
                if (status != null && status in URL_GONE && renew(f, source, status, offset)) continue
                if (status != null && status != 429 && status < 500) throw e
                val wait = retryDelaysMs.getOrNull(failures++) ?: throw e
                log("download $id: chunk at $offset failed ($e), retry $failures in $wait ms")
                delay(wait)
                continue
            }
            synchronized(f.p) {
                val p = f.p
                if (generation != p.generation) return // a chunk of the file this was before
                if (chunk.whole) {
                    f.startOver()
                    f.file.write(ByteBuffer.wrap(chunk.bytes), 0)
                    p.total = chunk.bytes.size.toLong()
                    p.done.set(0, f.chunks(p.total))
                } else if (p.total >= 0 && chunk.total != p.total) {
                    // Not the file this download began with (a re-resolved URL, or a stale
                    // .part): start over with what the server has now.
                    log("download $id: size changed ${p.total} -> ${chunk.total}, starting over")
                    f.startOver()
                } else {
                    if (p.total < 0) {
                        p.total = chunk.total
                        f.raf.setLength(chunk.total) // full size at once: chunks land in any order
                    }
                    f.file.write(ByteBuffer.wrap(chunk.bytes), offset)
                    p.done.set(index)
                    f.claimed.clear(index)
                }
            }
            return
        }
    }

    /** The stream URL expired or was refused: a new one, same offsets. False when that is used up. */
    private suspend fun renew(f: Fetch, failed: Source, status: Int, offset: Long): Boolean = f.renewing.withLock {
        if (f.source !== failed) return true // another chunk already got the new one
        if (f.resolves >= MAX_RESOLVES) return false
        f.resolves++
        log("download ${f.id}: HTTP $status at $offset, resolving the stream again")
        synchronized(lock) { resolved.remove(f.id) }
        f.source = source(f.id)
        true
    }

    /** One range response: its bytes and the file's [total] size; [whole] when the server ignored the range. */
    private class Chunk(val bytes: ByteArray, val total: Long, val whole: Boolean)

    /** A response that was neither 206 nor 200. */
    class HttpStatusException(val code: Int, id: String) : IOException("HTTP $code for $id")

    /** Cancellable: a paused or cancelled download drops its requests at once. */
    private suspend fun fetch(id: String, url: String, offset: Long, end: Long): Chunk {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgentFor(url))
            .header("Range", "bytes=$offset-$end")
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val chunk = try {
                        response.use { read(id, it, offset) }
                    } catch (e: IOException) {
                        return cont.resumeWithException(e)
                    }
                    cont.resume(chunk)
                }
            })
        }
    }

    private fun read(id: String, resp: Response, offset: Long): Chunk {
        if (resp.code != 206 && resp.code != 200) throw HttpStatusException(resp.code, id)
        val whole = resp.code == 200
        val total = if (whole) {
            -1L
        } else {
            resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: throw IOException("no Content-Range total")
        }
        val bytes = resp.body.bytes()
        if (bytes.isEmpty()) throw IOException("empty body at $offset")
        return Chunk(bytes, total, whole)
    }

    // ---- the index (under [lock]) ----

    private fun touch(id: String, f: File) {
        val now = nowMs()
        synchronized(lock) { index[id]?.lastUsed = now }
        f.setLastModified(now)
    }

    private fun publish() {
        idSet = index.keys.toSet()
        totalBytes = index.values.sumOf { it.size }
    }

    /** Least recently used first, never [keep] or a [protect]ed track. */
    private fun evict(keep: String) {
        var size = index.values.sumOf { it.size }
        if (size <= maxBytes) return
        val safe = protectedIds
        for ((id, item) in index.entries.sortedBy { it.value.lastUsed }) {
            if (size <= maxBytes) break
            if (id == keep || id in safe) continue
            size -= item.size
            index.remove(id)
            File(dir, "$id.m4a").delete()
            log("cache: evicted $id")
        }
    }

    companion object {
        private const val CHUNK = 1L shl 20
        /** Statuses that mean the stream URL itself is no good any more. */
        private val URL_GONE = setOf(403, 410)
        /** New stream URLs asked for within one download. */
        private const val MAX_RESOLVES = 2
        /** Stream URLs remembered. */
        private const val MAX_RESOLVED = 64
        private const val PRE_RESOLVE_PARALLEL = 3
        /** `.part` files kept for downloads that stopped. */
        private const val MAX_PARTS = 8

        /** Stream URLs are bound to the Innertube client that produced them (`c=` param). */
        fun userAgentFor(url: String): String {
            val client = Regex("[?&]c=([A-Z_]+)").find(url)?.groupValues?.get(1)
            return when (client) {
                "ANDROID", "ANDROID_MUSIC" -> YoutubeParsingHelper.getAndroidUserAgent(null)
                "IOS", "IOS_MUSIC" -> YoutubeParsingHelper.getIosUserAgent(null)
                "VISIONOS" -> YoutubeParsingHelper.getVisionOsUserAgent(null)
                else -> OkHttpDownloader.USER_AGENT
            }
        }
    }
}

/**
 * The host keeps Opus and AAC tracks apart, so falling back never replaces a file ExoPlayer has
 * open. [opus] until a client reports an Opus-in-MP4 file as not decodable
 * ([MusicController.onClientError]); from then on, for this session, every track is AAC.
 */
class TrackCaches(private val opusCache: TrackCache, private val aacCache: TrackCache) : TrackStore {
    override var opus = true
    val active: TrackCache get() = if (opus) opusCache else aacCache
    override suspend fun ensure(id: String): File = active.ensure(id)
    override suspend fun ensure(id: String, priority: DownloadPriority): File = active.ensure(id, priority)
    override fun cached(id: String): File? = active.cached(id)
    override fun cancel(id: String) { opusCache.cancel(id); aacCache.cancel(id) }
    override fun retain(ids: Collection<String>) { opusCache.retain(ids); aacCache.retain(ids) }
    override fun protect(ids: Collection<String>) { opusCache.protect(ids); aacCache.protect(ids) }
    override fun preResolve(ids: Collection<String>) = active.preResolve(ids)
}

/** What [MusicController] needs of the caches: the active one, and the Opus/AAC switch. */
interface TrackStore {
    var opus: Boolean
    /** The active cache's file for [id], downloading it first if need be. */
    suspend fun ensure(id: String): File
    /** The same, at [priority]: a higher one pauses the lower downloads. */
    suspend fun ensure(id: String, priority: DownloadPriority): File = ensure(id)
    /** The active cache's complete file for [id], or null. */
    fun cached(id: String): File?
    /** Stop downloading [id] (its bytes are kept); see [TrackCache.cancel]. */
    fun cancel(id: String) {}
    /** Stop every play-queue download that is not one of [ids]; see [TrackCache.retain]. */
    fun retain(ids: Collection<String>) {}
    /** The tracks eviction must leave alone: the playing one and the announced next. */
    fun protect(ids: Collection<String>) {}
    /** Look up the stream URLs of [ids] ahead of a tap; see [TrackCache.preResolve]. */
    fun preResolve(ids: Collection<String>) {}
}
