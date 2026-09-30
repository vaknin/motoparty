package com.kivan.motoparty.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Chunk retries, URL re-resolving and continuing a failed download, against a scripted server. */
class TrackCacheTest {
    private val id = "aaaaaaaaaaa"
    private val dir: File = Files.createTempDirectory("tracks").toFile()
    private val data = ByteArray(2_500) { (it * 7).toByte() }

    /** A range server over [data]. [script] answers request n (0-based) itself, or returns null. */
    private class Server(var data: ByteArray) {
        /** "<url path> <offset>" of every request. */
        val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        var script: (n: Int, chain: Interceptor.Chain) -> Response? = { _, _ -> null }

        fun status(chain: Interceptor.Chain, code: Int): Response = Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(ByteArray(0).toResponseBody()).build()

        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val (from, to) = chain.request().header("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
            val n = synchronized(requests) {
                requests += "${chain.request().url.encodedPath} $from"
                requests.size - 1
            }
            script(n, chain) ?: run {
                val end = minOf(to, data.size - 1)
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                    .header("Content-Range", "bytes $from-$end/${data.size}")
                    .body(data.copyOfRange(from, end + 1).toResponseBody()).build()
            }
        }.build()
    }

    private val server = Server(data)
    private var resolves = 0

    private fun cache(retries: List<Long> = listOf(0, 0)) = TrackCache(
        dir, server.http, { TrackCache.Source("http://host/v${++resolves}", webm = false) },
        CoroutineScope(SupervisorJob() + Dispatchers.IO), retryDelaysMs = retries, chunkBytes = 1_000,
        parallel = 1,
    )

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test
    fun `a failed chunk is asked for again at the same offset`() = runBlocking {
        server.script = { n, chain ->
            when (n) {
                1 -> throw IOException("timeout")
                2 -> server.status(chain, 503)
                else -> null
            }
        }
        val file = cache().ensure(id)
        assertArrayEquals(data, file.readBytes())
        assertEquals(listOf("/v1 0", "/v1 1000", "/v1 1000", "/v1 1000", "/v1 2000"), server.requests)
        assertEquals("the stream is resolved once", 1, resolves)
    }

    @Test
    fun `an expired URL is resolved again and the download goes on where it was`() = runBlocking {
        server.script = { n, chain -> if (n == 2) server.status(chain, 403) else null }
        val file = cache().ensure(id)
        assertArrayEquals(data, file.readBytes())
        assertEquals(listOf("/v1 0", "/v1 1000", "/v1 2000", "/v2 2000"), server.requests)
    }

    @Test
    fun `a download that fails for good is continued by the next one`() = runBlocking {
        val cache = cache()
        server.script = { n, _ -> if (n >= 1) throw IOException("no route") else null }
        try {
            cache.ensure(id)
            fail("should have thrown")
        } catch (e: IOException) {
            assertEquals("no route", e.message)
        }
        assertEquals("the first try and two retries", 4, server.requests.size)
        assertNull(cache.cached(id))

        server.script = { _, _ -> null }
        server.requests.clear()
        assertArrayEquals(data, cache.ensure(id).readBytes())
        assertEquals(listOf("/v2 1000", "/v2 2000"), server.requests)
    }

    @Test
    fun `a continued download starts over when the file is not the same size`() = runBlocking {
        val cache = cache(retries = emptyList())
        server.script = { n, _ -> if (n >= 1) throw IOException("no route") else null }
        runCatching { cache.ensure(id) }
        server.script = { _, _ -> null }
        server.data = ByteArray(1_800) { (it * 3).toByte() }
        server.requests.clear()
        assertArrayEquals(server.data, cache.ensure(id).readBytes())
        assertEquals(listOf("/v2 1000", "/v2 0", "/v2 1000"), server.requests)
    }

    @Test
    fun `a refused video is not retried`() = runBlocking {
        server.script = { _, chain -> server.status(chain, 404) }
        val e = runCatching { cache().ensure(id) }.exceptionOrNull()
        assertTrue("$e", e is TrackCache.HttpStatusException && e.code == 404)
        assertEquals(1, server.requests.size)
    }
}
