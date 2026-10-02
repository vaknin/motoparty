package com.kivan.motoparty.lyrics

import com.kivan.motoparty.music.Track
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Live LRCLIB; skipped unless `./gradlew test -Pnetwork`. */
class LyricsNetworkTest {
    @Before fun onlyWithNetwork() = assumeTrue(System.getProperty("motoparty.network") == "true")

    @Test
    fun aWellKnownSongHasSyncedLyrics() {
        val track = Track("fJ9rUzIMcZQ", "Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official", durationMs = 355_000)
        val lines = LyricsSource(OkHttpClient()).find(track)
        println("lyrics of ${track.title}: ${lines?.size} lines, first at ${lines?.firstOrNull()?.ms} ms")
        assertTrue(lines != null && lines.size > 20)
        assertTrue(lines!!.zipWithNext().all { (a, b) -> a.ms <= b.ms })
    }
}
