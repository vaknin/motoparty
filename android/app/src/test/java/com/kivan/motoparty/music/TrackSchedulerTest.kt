package com.kivan.motoparty.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parallel ranges, the priority scheduler, cancelling, the stream-URL cache, the in-memory index
 * and protected eviction, against a scripted range server (url path = `/<track id>`).
 */
class TrackSchedulerTest {
    private val dir: File = Files.createTempDirectory("tracks").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val a = "aaaaaaaaaaa"
    private val b = "bbbbbbbbbbb"
    private val c = "ccccccccccc"

    private fun bytes(id: String, size: Int = 4_000) = ByteArray(size) { (it * 7 + id[0].code).toByte() }

    /** Serves `bytes(id)` for `/<id>`. A [gates] entry holds that track's requests until opened. */
    private inner class Server(val size: Int = 4_000) {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val gates = ConcurrentHashMap<String, CountDownLatch>()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.encodedPath.removePrefix("/")
            val (from, to) = chain.request().header("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
            requests += "$id $from"
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                gates[id]?.let {
                    // Interrupted by OkHttp when the call is cancelled: that is the pause.
                    while (!it.await(10, TimeUnit.MILLISECONDS)) {
                        if (chain.call().isCanceled()) throw java.io.IOException("Canceled")
                    }
                }
                Thread.sleep(5)
                val data = bytes(id, size)
                val end = minOf(to, data.size - 1)
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                    .header("Content-Range", "bytes $from-$end/${data.size}")
                    .body(data.copyOfRange(from, end + 1).toResponseBody()).build()
            } finally {
                inFlight.decrementAndGet()
            }
        }.build()

        fun of(id: String) = synchronized(requests) { requests.filter { it.startsWith("$id ") } }
    }

    private val server = Server()
    private val resolves: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var now = 1_000_000L

    private fun cache(
        maxBytes: Long = 1L shl 30,
        parallel: Int = 4,
        logs: MutableList<String>? = null,
        onChange: () -> Unit = {},
    ) = TrackCache(
        dir, server.http, { id -> resolves += id; TrackCache.Source("http://host/$id", webm = false) },
        scope, maxBytes = maxBytes, retryDelaysMs = listOf(0), chunkBytes = 1_000, parallel = parallel,
        log = { logs?.add(it) }, nowMs = { now }, onChange = onChange,
    )

    @After fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            delay(5)
        }
    }

    // ---- M3 ----

    @Test
    fun `after the first chunk the rest is fetched as parallel ranges`() = runBlocking {
        val big = Server(size = 9_500)
        val cache = TrackCache(
            dir, big.http, { TrackCache.Source("http://host/$it", false) }, scope, chunkBytes = 1_000, parallel = 4,
        )
        val probe = CountDownLatch(1)
        big.gates[a] = probe
        val file = async { cache.ensure(a) }
        waitUntil("probe asked") { big.of(a).isNotEmpty() }
        delay(50)
        assertEquals("only the size probe at first", listOf("$a 0"), big.of(a))
        val rest = CountDownLatch(1)
        big.gates[a] = rest
        probe.countDown()
        waitUntil("four ranges in flight") { big.inFlight.get() == 4 }
        delay(50)
        assertEquals("the probe and four more", 5, big.of(a).size)
        big.gates.remove(a)
        rest.countDown()
        assertArrayEquals(bytes(a, 9_500), file.await().readBytes())
        assertEquals("every chunk once", (0 until 10).map { "$a ${it * 1000}" }.toSet(), big.of(a).toSet())
        assertEquals(10, big.of(a).size)
        assertTrue("never more than 4 at once", big.maxInFlight.get() <= 4)
    }

    @Test
    fun `a stream is resolved once for an hour and preResolve does it ahead`() = runBlocking {
        val cache = cache()
        cache.preResolve(listOf(a, b, "bad id"))
        waitUntil("both resolved") { resolves.size == 2 }
        // Pre-resolves run in parallel: their order is not fixed.
        assertEquals(setOf(a, b), resolves.toSet())
        assertTrue("nothing is downloaded by a pre-resolve", server.requests.isEmpty())
        cache.ensure(a)
        assertEquals("the download used the pre-resolved URL", 2, resolves.size)
        cache.preResolve(listOf(a, b))
        delay(50)
        assertEquals("cached / fresh ones are skipped", 2, resolves.size)
        now += 3_600_001
        cache.ensure(b)
        assertEquals("an hour later it is looked up again", 3, resolves.size)
        assertEquals(b, resolves.last())
    }

    // ---- M4 ----

    @Test
    fun `a current download pauses a prefetch, which goes on from its bytes afterwards`() = runBlocking {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val cache = cache(parallel = 1, logs = logs)
        // b: first chunk passes, the second is held at the server.
        val gate = CountDownLatch(1)
        val first = CountDownLatch(1)
        server.gates[b] = first
        val prefetch = async { cache.ensure(b, DownloadPriority.PREFETCH) }
        waitUntil("probe asked") { server.of(b).size == 1 }
        server.gates[b] = gate
        first.countDown()
        waitUntil("second chunk asked") { server.of(b).size == 2 }

        val current = withTimeout(5_000) { cache.ensure(a) } // finishes while b's request is still held
        assertArrayEquals(bytes(a), current.readBytes())
        assertNull("b is not done", cache.cached(b))
        assertTrue(cache.isDownloading(b))

        gate.countDown()
        assertArrayEquals(bytes(b), withTimeout(5_000) { prefetch.await() }.readBytes())
        val asked = server.of(b)
        assertEquals("chunk 0 was fetched once: its bytes were kept", 1, asked.count { it == "$b 0" })
        assertEquals("b is resolved once", 1, resolves.count { it == b })
        assertTrue(logs.toString(), logs.any { it.contains("continuing") || it.contains("waits for") })
    }

    @Test
    fun `downloads run one at a time, highest priority first, newest current first`() = runBlocking {
        val cache = cache(parallel = 1)
        val hold = CountDownLatch(1)
        server.gates[a] = hold
        val order = Collections.synchronizedList(mutableListOf<String>())
        val first = launch { cache.ensure(a); order += a }
        waitUntil("a started") { server.of(a).isNotEmpty() }
        val collection = launch { cache.ensure(c, DownloadPriority.COLLECTION); order += c }
        val next = launch { cache.ensure(b, DownloadPriority.NEXT); order += b }
        delay(100)
        assertTrue("lower priorities wait for the current one", server.of(b).isEmpty() && server.of(c).isEmpty())
        hold.countDown()
        first.join(); next.join(); collection.join()
        assertEquals(listOf(a, b, c), order.toList())
        assertEquals("$b 0", server.requests.first { !it.startsWith(a) })
    }

    @Test
    fun `a newer current track takes over from an older one`() = runBlocking {
        val cache = cache(parallel = 1)
        val hold = CountDownLatch(1)
        server.gates[a] = hold
        val old = async { cache.ensure(a) }
        waitUntil("a started") { server.of(a).isNotEmpty() }
        assertArrayEquals(bytes(b), withTimeout(5_000) { cache.ensure(b) }.readBytes())
        assertFalse(old.isCompleted)
        hold.countDown()
        assertArrayEquals(bytes(a), old.await().readBytes())
    }

    @Test
    fun `cancelling the only waiter stops the download and keeps its bytes`() = runBlocking {
        val cache = cache(parallel = 1)
        val first = CountDownLatch(1)
        server.gates[a] = first
        val job = launch { cache.ensure(a, DownloadPriority.PREFETCH) }
        waitUntil("probe asked") { server.of(a).size == 1 }
        server.gates[a] = CountDownLatch(1) // held for good
        first.countDown()
        waitUntil("second chunk asked") { server.of(a).size == 2 }
        job.cancelAndJoin()
        waitUntil("stopped") { !cache.isDownloading(a) }
        assertNull(cache.cached(a))
        assertTrue(File(dir, "$a.m4a.part").isFile)

        server.gates.clear()
        assertArrayEquals(bytes(a), cache.ensure(a).readBytes())
        assertEquals("chunk 0 is not fetched again", 1, server.of(a).count { it == "$a 0" })
    }

    @Test
    fun `cancel and retain stop downloads and their waiters`() = runBlocking {
        val cache = cache(parallel = 1)
        listOf(a, b, c).forEach { server.gates[it] = CountDownLatch(1) }
        val wa = async { runCatching { cache.ensure(a, DownloadPriority.NEXT) } }
        val wb = async { runCatching { cache.ensure(b, DownloadPriority.PREFETCH) } }
        val wc = async { runCatching { cache.ensure(c, DownloadPriority.COLLECTION) } }
        waitUntil("all queued") { listOf(a, b, c).all(cache::isDownloading) }
        cache.retain(listOf(a))
        assertTrue(wb.await().exceptionOrNull() is CancellationException)
        assertTrue("a is kept", cache.isDownloading(a))
        assertTrue("collection downloads are not the queue's to stop", cache.isDownloading(c))
        cache.cancel(a)
        assertTrue(wa.await().exceptionOrNull() is CancellationException)
        server.gates.values.forEach { it.countDown() }
        server.gates.clear()
        assertTrue(withTimeout(5_000) { wc.await() }.isSuccess)
        // And a cancelled track can be asked for again.
        assertArrayEquals(bytes(a), cache.ensure(a).readBytes())
    }

    @Test
    fun `many requests for one track share one download`() = runBlocking {
        val cache = cache()
        val files = (1..20).map { async(Dispatchers.IO) { cache.ensure(a, DownloadPriority.entries[it % 4]) } }
        files.forEach { assertArrayEquals(bytes(a), it.await().readBytes()) }
        assertEquals(listOf(a), resolves.toList())
        assertEquals(4, server.of(a).size)
    }

    // ---- M6, M8 ----

    @Test
    fun `ids and size are kept in memory, from the start-up scan on`() = runBlocking {
        File(dir, "$c.m4a").writeBytes(ByteArray(300))
        File(dir, "$b.m4a.part").writeBytes(ByteArray(10))
        val changed = CompletableDeferred<Unit>()
        val cache = cache(onChange = { changed.complete(Unit) })
        withTimeout(5_000) { changed.await() }
        cache.awaitScan()
        assertEquals(setOf(c), cache.ids())
        assertEquals(300L, cache.sizeBytes())
        assertFalse("a stale .part is removed", File(dir, "$b.m4a.part").exists())
        cache.ensure(a)
        assertEquals(setOf(a, c), cache.ids())
        assertEquals(4_300L, cache.sizeBytes())
        // The directory is not read again: a file that appears behind its back is not counted.
        File(dir, "zzzzzzzzzzz.m4a").writeBytes(ByteArray(50))
        assertEquals(4_300L, cache.sizeBytes())
    }

    @Test
    fun `eviction drops the least recently used track but never a protected one`() = runBlocking {
        val cache = cache(maxBytes = 9_000)
        cache.ensure(a); now += 10
        cache.ensure(b); now += 10
        cache.protect(listOf(a))
        cache.ensure(c)
        assertEquals("a is the oldest but protected: b went", setOf(a, c), cache.ids())
        assertNull(cache.cached(b))
        assertNotNull(cache.cached(a))
        assertEquals(8_000L, cache.sizeBytes())

        cache.protect(emptyList())
        now += 10
        cache.ensure(a) // touched: c is now the oldest
        now += 10
        cache.ensure(b)
        assertEquals(setOf(a, b), cache.ids())
    }

    @Test
    fun `the stores forward to the caches`() = runBlocking {
        val caches = TrackCaches(cache(), TrackCache(File(dir, "aac"), server.http, { TrackCache.Source("http://host/$it", false) }, scope))
        assertArrayEquals(bytes(a), caches.ensure(a, DownloadPriority.NEXT).readBytes())
        caches.protect(listOf(a)); caches.retain(listOf(a)); caches.cancel(b); caches.preResolve(listOf(b))
        waitUntil("pre-resolved") { resolves.contains(b) }
    }
}
