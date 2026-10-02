package com.kivan.motoparty.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of artist pages (2026-10-02): credit splitting, the Ride pick, the album fallback and the song filter. */
class ArtistPageTest {
    @Test
    fun creditsSplitIntoNames() {
        assertEquals(listOf("Queen"), Catalog.splitArtists("Queen"))
        assertEquals(listOf("Queen", "David Bowie"), Catalog.splitArtists("Queen & David Bowie"))
        assertEquals(listOf("A", "B", "C"), Catalog.splitArtists("A, B & C"))
        assertEquals(listOf("Drake", "Rihanna"), Catalog.splitArtists("Drake feat. Rihanna"))
        assertEquals(listOf("Drake", "Rihanna"), Catalog.splitArtists("Drake Feat. Rihanna"))
        assertEquals(listOf("Drake", "Rihanna"), Catalog.splitArtists("Drake ft. Rihanna"))
        assertEquals(listOf("Drake", "Rihanna"), Catalog.splitArtists("Drake featuring Rihanna"))
        assertEquals(listOf("Skrillex", "Fred again.."), Catalog.splitArtists("Skrillex x Fred again.."))
        // Not separators: a capital X, an "&" or "x" inside a word, a comma with no space after it.
        assertEquals(listOf("Malcolm X Band"), Catalog.splitArtists("Malcolm X Band"))
        assertEquals(listOf("Simon&Garfunkel"), Catalog.splitArtists("Simon&Garfunkel"))
        assertEquals(listOf("Xzibit"), Catalog.splitArtists("Xzibit"))
        assertEquals(listOf("Crosby,Stills"), Catalog.splitArtists("Crosby,Stills"))
        assertEquals(listOf("Fifty Fifty"), Catalog.splitArtists("Fifty Fifty"))
        assertEquals(emptyList<String>(), Catalog.splitArtists(""))
    }

    @Test
    fun theRideTapPicksTheWholeCreditThenItsNames() {
        val sg = ArtistItem("UC1", "Simon & Garfunkel")
        val paul = ArtistItem("UC2", "Paul Simon")
        val queen = ArtistItem("UC3", "Queen")
        val bowie = ArtistItem("UC4", "David Bowie")
        assertEquals(sg, Catalog.pickArtist(listOf(paul, sg), "Simon & Garfunkel"))
        assertEquals(queen, Catalog.pickArtist(listOf(bowie, queen), "Queen & David Bowie"))
        assertEquals(bowie, Catalog.pickArtist(listOf(bowie), "Queen & David Bowie"))
        val loud = ArtistItem("UC5", "QUEEN")
        assertEquals(loud, Catalog.pickArtist(listOf(paul, loud), "queen"))
        assertNull(Catalog.pickArtist(listOf(paul), "Simon & Garfunkel")) // the caller takes the top hit
        assertNull(Catalog.pickArtist(emptyList(), "Queen"))
    }

    private fun album(id: String, artist: String) = CollectionItem(id, "Album $id", artist)

    @Test
    fun albumsComeFromReleasesElseAFilteredSearch() {
        var searched = false
        val releases = listOf(album("r1", "Pink Floyd"), album("r2", ""), album("r1", "Pink Floyd"))
        val fromTab = Catalog.artistAlbums(listOf("Pink Floyd"), releases) { searched = true; emptyList() }
        assertEquals(listOf("r1", "r2"), fromTab.map { it.id })
        assertFalse("the search runs only without releases", searched)

        val search = listOf(album("s1", "Pink Floyd - Topic"), album("s2", "Various Artists"), album("s3", "pink floyd"),
            album("s4", "Pink Floyd Tribute"))
        val fallback = Catalog.artistAlbums(listOf("Pink Floyd"), emptyList()) { searched = true; search }
        assertTrue(searched)
        assertEquals(listOf("s1", "s3"), fallback.map { it.id })

        val many = List(80) { album("a$it", "X") }
        assertEquals(Catalog.ARTIST_ALBUMS, Catalog.artistAlbums(listOf("X"), many) { emptyList() }.size)
    }

    private fun song(id: String, artist: String) = Track(id, "Song $id", artist, durationMs = 1000)

    @Test
    fun topSongsAreTheArtistsElseTheSearchsOwn() {
        val songs = listOf(song("1", "Queen"), song("2", "Queen Tribute Band"), song("3", "Queen & David Bowie"),
            song("4", "David Bowie"), song("5", "queen"))
        assertEquals(listOf("1", "3", "5"), Catalog.artistSongs(songs, listOf("Queen")).map { it.id })
        // Nobody credited by that exact name: the search's top songs as they came.
        assertEquals(listOf("1", "2", "3", "4", "5"), Catalog.artistSongs(songs, listOf("Freddie")).map { it.id })
        val many = List(40) { song("$it", "Queen") }
        assertEquals(Catalog.ARTIST_SONGS, Catalog.artistSongs(many, listOf("Queen")).size)
        // The official channel's other name counts too.
        val hebrew = listOf(song("h1", "ישי ריבו"), song("h2", "Ishay Ribo"), song("h3", "Other"))
        assertEquals(listOf("h1", "h2"), Catalog.artistSongs(hebrew, listOf("Ishay Ribo", "ישי ריבו")).map { it.id })
    }

    @Test
    fun channelNamesDropTheDecorations() {
        assertEquals(listOf("ישי ריבו", "Ishay Ribo"), Catalog.channelNames("ישי ריבו | Ishay Ribo"))
        assertEquals(listOf("Queen"), Catalog.channelNames("Queen Official"))
        assertEquals(listOf("Queen"), Catalog.channelNames("QueenVEVO"))
        assertEquals(listOf("Moby"), Catalog.channelNames("Moby - Topic"))
        assertEquals(listOf("Pink Floyd"), Catalog.channelNames("Pink Floyd"))
        assertEquals(listOf("Official Secrets"), Catalog.channelNames("Official Secrets"))
    }

    @Test
    fun fallbackAlbumsMatchAnyOfTheNames() {
        val search = listOf(album("s1", "ישי ריבו"), album("s2", "Ishay Ribo"), album("s3", "Someone"))
        assertEquals(listOf("s1", "s2"), Catalog.artistAlbums(listOf("Ishay Ribo", "ישי ריבו"), emptyList()) { search }.map { it.id })
    }
}
