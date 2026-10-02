package com.kivan.motoparty.lyrics

import com.kivan.motoparty.music.Track
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** LRCLIB ranking, fallback and title cleaning on canned (made-up) search bodies under resources/lrclib. */
class LyricsSourceTest {
    private fun res(name: String) = javaClass.getResource("/lrclib/$name")!!.readText()

    @Test
    fun ranksByPlausibilityThenDurationAndDropsOtherRecordings() {
        val ranked = LyricsSource.rank(LyricsSource.parseSearch(res("fields.json")), durationMs = 201_000)
        // 1: not synced; 2: 29 s longer; 5: the same sync as 4; 3: plausible timing beats it.
        assertEquals(listOf(4L, 6L, 3L), ranked.map { it.id })
    }

    @Test
    fun unknownDurationKeepsEveryLength() {
        val ranked = LyricsSource.rank(LyricsSource.parseSearch(res("fields.json")), durationMs = 0)
        assertEquals(setOf(2L, 3L, 4L, 6L), ranked.map { it.id }.toSet())
    }

    @Test
    fun fieldSearchFirst() {
        val asked = ArrayList<HttpUrl>()
        val source = LyricsSource { url -> asked += url; res("fields.json") }
        val lines = source.find(Track("abc", "The Made Up Band - Paper Lantern (Official Video)", "The Made Up Band", durationMs = 201_000))!!
        assertEquals(1, asked.size)
        assertEquals("The Made Up Band", asked[0].queryParameter("artist_name"))
        assertEquals("Paper Lantern", asked[0].queryParameter("track_name"))
        assertEquals(listOf(10_000L, 14_000L, 18_000L, 22_000L), lines.map { it.ms })
        assertEquals("", lines[2].text)
    }

    @Test
    fun plainQueryWhenTheFieldsFindNoSync() {
        val asked = ArrayList<HttpUrl>()
        val source = LyricsSource { url -> asked += url; if (url.queryParameter("q") == null) res("plain-only.json") else res("query.json") }
        val lines = source.find(Track("abc", "Tin Kite (feat. Someone) [Lyric Video]", "Nobody Real - Topic", durationMs = 180_000))!!
        assertEquals(2, asked.size)
        assertEquals("Nobody Real Tin Kite", asked[1].queryParameter("q"))
        assertEquals("Climbing higher", lines[1].text)
    }

    @Test
    fun noneAnywhereIsNull() {
        assertNull(LyricsSource { "[]" }.find(Track("abc", "Nothing", "Nobody", durationMs = 1000)))
    }

    @Test(expected = IOException::class)
    fun unreachableThrows() {
        LyricsSource { throw IOException("no coverage") }.find(Track("abc", "Song", "Artist", durationMs = 1000))
    }

    @Test
    fun youtubeTitlesAreCleaned() {
        fun clean(title: String, artist: String = "Made Up") = LyricsSource.clean(title, artist)
        assertEquals("Made Up" to "Song", clean("Made Up - Song (Official Music Video)"))
        assertEquals("Made Up" to "Song", clean("Song [Lyric Video]"))
        assertEquals("Made Up" to "Song", clean("Song (Lyrics)"))
        assertEquals("Made Up" to "Song", clean("Song (Audio)"))
        assertEquals("Made Up" to "Song", clean("Song (Official Visualizer)"))
        assertEquals("Made Up" to "Song", clean("Song (Remastered 2011)"))
        assertEquals("Made Up" to "Song", clean("Song ft. Somebody Else"))
        assertEquals("Made Up" to "Song", clean("Song (feat. Somebody Else) [HD]"))
        assertEquals("Made Up" to "Song", clean("Song", "Made Up - Topic"))
        assertEquals("Made Up" to "Song", clean("Song", "Made UpVEVO"))
        assertEquals("Queen" to "Bohemian Rhapsody", clean("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official"))
        // Somebody else on the left of the dash is part of the title.
        assertEquals("Made Up" to "Other - Song", clean("Other - Song"))
        // Keeps what is not noise.
        assertEquals("Made Up" to "Song (Live at Home)", clean("Song (Live at Home)"))
        // Never empty.
        assertEquals("Made Up" to "(Official Video)", clean("(Official Video)"))
    }
}
