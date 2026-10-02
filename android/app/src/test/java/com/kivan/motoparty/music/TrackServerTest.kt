package com.kivan.motoparty.music

import com.kivan.motoparty.lyrics.LyricsAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TrackServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File.createTempFile("track", ".m4a").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
    private val lyricsBody = """{"id":"abc-_1","source":"lrclib","lines":[{"ms":1000,"text":"Ünï","words":[{"ms":1000,"text":"Ünï"}]}]}"""
    private val lyricsAsked = ArrayList<String>()
    private val server = TrackServer(scope, { id -> if (id == "abc-_1") file else null }, port = 0, lyrics = { id ->
        synchronized(lyricsAsked) { lyricsAsked += id }
        when (id) {
            "abc-_1" -> LyricsAnswer.Ok(lyricsBody.toByteArray())
            "looking" -> LyricsAnswer.Busy
            else -> LyricsAnswer.NotFound
        }
    })

    @Before fun start() = server.start()

    @After fun stop() {
        server.stop()
        scope.cancel()
        file.delete()
    }

    private fun get(path: String, range: String? = null): HttpURLConnection =
        (URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection).apply {
            range?.let { setRequestProperty("Range", it) }
        }

    @Test
    fun fullFile() {
        val c = get("/track/abc-_1.m4a")
        assertEquals(200, c.responseCode)
        assertEquals("audio/mp4", c.contentType)
        assertArrayEquals(file.readBytes(), c.inputStream.readBytes())
    }

    @Test
    fun rangeRequest() {
        val c = get("/track/abc-_1.m4a", "bytes=10-19")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 10-19/1000", c.getHeaderField("Content-Range"))
        assertArrayEquals(file.readBytes().copyOfRange(10, 20), c.inputStream.readBytes())
    }

    @Test
    fun suffixAndOpenRanges() {
        assertArrayEquals(file.readBytes().copyOfRange(990, 1000), get("/track/abc-_1.m4a", "bytes=-10").inputStream.readBytes())
        assertArrayEquals(file.readBytes().copyOfRange(995, 1000), get("/track/abc-_1.m4a", "bytes=995-").inputStream.readBytes())
        assertEquals(416, get("/track/abc-_1.m4a", "bytes=5000-").responseCode)
    }

    @Test
    fun onlyCachedTracksAreServed() {
        assertEquals(404, get("/track/other.m4a").responseCode)
        assertEquals(404, get("/").responseCode)
        assertEquals(404, get("/track/..%2Fetc.m4a").responseCode)
    }

    @Test
    fun lyricsAnswers() {
        val ok = get("/lyrics/abc-_1.json")
        assertEquals(200, ok.responseCode)
        assertEquals("application/json; charset=utf-8", ok.contentType)
        assertEquals(lyricsBody, ok.inputStream.readBytes().toString(Charsets.UTF_8))
        val busy = get("/lyrics/looking.json")
        assertEquals(503, busy.responseCode)
        assertEquals("5", busy.getHeaderField("Retry-After"))
        assertEquals(404, get("/lyrics/none.json").responseCode)
        // An invalid id never reaches the cache.
        assertEquals(404, get("/lyrics/..%2Fetc.json").responseCode)
        assertEquals(404, get("/lyrics/abc-_1.lrc").responseCode)
        assertEquals(listOf("abc-_1", "looking", "none"), synchronized(lyricsAsked) { lyricsAsked.toList() })
    }

    @Test
    fun lyricsHead() {
        val c = get("/lyrics/abc-_1.json").apply { requestMethod = "HEAD" }
        assertEquals(200, c.responseCode)
        assertEquals(lyricsBody.toByteArray().size.toLong(), c.contentLengthLong)
    }

    @Test
    fun trackSocketsAreBackgroundClass() {
        java.net.Socket("127.0.0.1", server.boundPort).use { s ->
            server.mark(s)
            assertEquals(0x20, TrackServer.TRAFFIC_CLASS)
            assertEquals(TrackServer.TRAFFIC_CLASS, s.trafficClass)
        }
    }

    @Test
    fun rangeParser() {
        assertEquals(0L to 99L, TrackServer.parseRange("bytes=0-99", 1000))
        assertEquals(900L to 999L, TrackServer.parseRange("bytes=900-5000", 1000))
        assertNull(TrackServer.parseRange("items=0-1", 1000))
        assertNull(TrackServer.parseRange("bytes=0-1,5-6", 1000))
        assertEquals(TrackServer.INVALID, TrackServer.parseRange("bytes=1000-", 1000))
    }
}
