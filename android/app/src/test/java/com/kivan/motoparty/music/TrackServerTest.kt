package com.kivan.motoparty.music

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
    private val server = TrackServer(scope, { id -> if (id == "abc-_1") file else null }, port = 0)

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
    fun rangeParser() {
        assertEquals(0L to 99L, TrackServer.parseRange("bytes=0-99", 1000))
        assertEquals(900L to 999L, TrackServer.parseRange("bytes=900-5000", 1000))
        assertNull(TrackServer.parseRange("items=0-1", 1000))
        assertNull(TrackServer.parseRange("bytes=0-1,5-6", 1000))
        assertEquals(TrackServer.INVALID, TrackServer.parseRange("bytes=1000-", 1000))
    }
}
