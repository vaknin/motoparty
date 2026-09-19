package com.kivan.motoparty.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloaded tracks in `<dir>/<id>.m4a`, LRU-evicted (by mtime, touched on use) above
 * [maxBytes]. Concurrent requests for the same id share one download. Pure JVM.
 */
class TrackCache(
    private val dir: File,
    private val http: OkHttpClient,
    private val resolve: suspend (id: String) -> String,
    private val scope: CoroutineScope,
    private val maxBytes: Long = 1L shl 30,
) {
    private val inflight = ConcurrentHashMap<String, Deferred<File>>()

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** The complete file if it is cached, else null. Never returns a partial download. */
    fun cached(id: String): File? {
        if (!isValidTrackId(id)) return null
        return File(dir, "$id.m4a").takeIf { it.isFile && it.length() > 0 }
    }

    suspend fun ensure(id: String): File {
        cached(id)?.let { touch(it); return it }
        val job = inflight.computeIfAbsent(id) { key ->
            scope.async(Dispatchers.IO) {
                try {
                    download(key)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return job.await()
    }

    /** Fire-and-forget warm-up of the next queue item. */
    fun prefetch(id: String) {
        if (cached(id) != null) return
        scope.async(Dispatchers.IO) { runCatching { ensure(id) } }
    }

    fun isDownloading(id: String) = inflight.containsKey(id)

    private suspend fun download(id: String): File = withContext(Dispatchers.IO) {
        val url = resolve(id)
        val part = File(dir, "$id.m4a.part")
        val target = File(dir, "$id.m4a")
        RandomAccessFile(part, "rw").use { out ->
            out.setLength(0)
            var offset = 0L
            var total = -1L
            // googlevideo throttles long single responses; ~1 MiB range requests stay fast.
            while (total < 0 || offset < total) {
                ensureActive()
                val end = if (total < 0) offset + CHUNK - 1 else minOf(offset + CHUNK, total) - 1
                val request = Request.Builder().url(url)
                    .header("User-Agent", userAgentFor(url))
                    .header("Range", "bytes=$offset-$end")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (resp.code != 206 && resp.code != 200) throw IOException("HTTP ${resp.code} for $id")
                    total = if (resp.code == 200) {
                        resp.body.contentLength()
                    } else {
                        resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: throw IOException("no Content-Range total")
                    }
                    val bytes = resp.body.bytes()
                    if (bytes.isEmpty()) throw IOException("empty body at $offset")
                    if (resp.code == 200) out.setLength(0)
                    out.write(bytes)
                    offset = if (resp.code == 200) bytes.size.toLong() else offset + bytes.size
                    if (resp.code == 200) total = offset
                }
            }
        }
        if (!part.renameTo(target)) throw IOException("rename failed for $id")
        evict(keep = target)
        target
    }

    private fun touch(f: File) {
        f.setLastModified(System.currentTimeMillis())
    }

    @Synchronized
    private fun evict(keep: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".m4a") }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (f in files) {
            if (size <= maxBytes) break
            if (f == keep) continue
            size -= f.length()
            f.delete()
        }
    }

    fun sizeBytes(): Long = dir.listFiles { f -> f.name.endsWith(".m4a") }?.sumOf { it.length() } ?: 0

    companion object {
        private const val CHUNK = 1L shl 20

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
