package com.kivan.motoparty.lyrics

import com.kivan.motoparty.music.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LyricsCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = Files.createTempDirectory("lyrics").toFile()
    private val changes = LinkedBlockingQueue<String>()
    private var now = 1_000_000L
    private val track = Track("abc", "Song", "Artist", durationMs = 200_000)
    private val lines = Lrc.parse("[00:01.00]Made up words")

    @After fun done() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun cache(find: (Track) -> List<LyricLine>?) =
        LyricsCache(dir, scope, find, onChange = { changes.put(it) }, nowMs = { now })

    private fun settle() = assertEquals("abc", changes.poll(5, TimeUnit.SECONDS))

    @Test
    fun foundIsServedAndKeptOnDisk() {
        val calls = AtomicInteger()
        val c = cache { calls.incrementAndGet(); lines }
        c.know(listOf(track))
        assertEquals(LyricsAnswer.Busy, c.answer("abc"))
        settle()
        val ok = c.answer("abc") as LyricsAnswer.Ok
        assertEquals(LyricsBody("abc", lines = lines), LyricsBody.decode(ok.body.toString(Charsets.UTF_8)))
        assertEquals(LyricsView("abc", LyricsView.Kind.FOUND, lines), c.view("abc"))
        // A new cache (a restart) answers from the disk, without knowing the track or asking LRCLIB.
        val again = cache { error("not asked") }
        assertTrue(again.answer("abc") is LyricsAnswer.Ok)
        assertEquals(1, calls.get())
    }

    @Test
    fun notFoundIsRememberedForThirtyDays() {
        val calls = AtomicInteger()
        val c = cache { calls.incrementAndGet(); null }
        c.know(listOf(track))
        c.request("abc")
        settle()
        assertEquals(LyricsAnswer.NotFound, c.answer("abc"))
        assertEquals(LyricsView.Kind.NOT_FOUND, c.view("abc").kind)
        now += 29L * 24 * 3600 * 1000
        c.request("abc")
        assertEquals(1, calls.get())
        // Past the TTL (the file's mtime is real time): a fresh cache looks again.
        now = System.currentTimeMillis() + 31L * 24 * 3600 * 1000
        val later = cache { calls.incrementAndGet(); null }
        later.know(listOf(track))
        assertEquals(LyricsAnswer.Busy, later.answer("abc"))
        settle()
        assertEquals(2, calls.get())
    }

    @Test
    fun failuresAreNotCachedAndRetriedOncePerMinute() {
        val calls = AtomicInteger()
        var offline = true
        val c = cache { calls.incrementAndGet(); if (offline) throw IOException("no coverage") else lines }
        c.know(listOf(track))
        c.request("abc")
        settle()
        assertEquals(LyricsView.Kind.OFFLINE, c.view("abc").kind)
        // Still offline: 503, and no new lookup within the retry wait.
        assertEquals(LyricsAnswer.Busy, c.answer("abc"))
        c.request("abc")
        assertEquals(1, calls.get())
        now += 20_000
        offline = false
        c.request("abc")
        settle()
        assertTrue(c.answer("abc") is LyricsAnswer.Ok)
        assertEquals(2, calls.get())
    }

    @Test
    fun oneLookupAtATimePerId() {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        val c = cache { calls.incrementAndGet(); gate.await(); lines }
        c.know(listOf(track))
        repeat(5) { c.request("abc") }
        repeat(3) { assertEquals(LyricsAnswer.Busy, c.answer("abc")) }
        gate.countDown()
        settle()
        assertEquals(1, calls.get())
    }

    @Test
    fun unknownTracksWaitForTheirTitle() {
        val c = cache { lines }
        assertEquals(LyricsAnswer.NotFound, c.answer("abc"))
        assertEquals(LyricsAnswer.NotFound, c.answer("../etc"))
        c.request("abc") // a download of a track we have no title for
        settle()
        c.know(listOf(track))
        settle()
        assertTrue(c.answer("abc") is LyricsAnswer.Ok)
    }
}
