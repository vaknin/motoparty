package com.kivan.motoparty.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** PROTOCOL.md "Commands", *The clarifying question*: `play album <artist>` is any album of theirs. */
class AnyAlbumTest {
    private fun album(id: String, title: String, artist: String) = CollectionItem(id, title, artist)

    private val moby = listOf(
        album("a", "Play", "Moby"),
        album("b", "18", "Moby"),
        album("c", "Moby Grape", "Moby Grape"),
        album("d", "Hotel", "MOBY"),
        album("e", "Innocents", "Moby"),
        album("f", "Reprise", "Moby"),
        album("g", "Animal Rights", "Moby"),
    )

    @Test
    fun anArtistNameGivesOneOfTheirTopAlbums() {
        val picked = (0 until 200).map { Catalog.anyAlbum(moby, "moby", Random(it))!!.id }.toSet()
        // The top five by that artist, whatever the case of the name; never another artist's.
        assertEquals(setOf("a", "b", "d", "e", "f"), picked)
    }

    @Test
    fun anAlbumTitleIsNotAnArtistRequest() {
        assertNull(Catalog.anyAlbum(moby, "play", Random(1)))
        // A self-titled album is asked for by its title.
        assertNull(Catalog.anyAlbum(listOf(album("m", "Metallica", "Metallica"), album("n", "Load", "Metallica")), "metallica"))
        assertNull(Catalog.anyAlbum(emptyList(), "moby"))
    }

    @Test
    fun aSingleAlbumByTheArtistIsThatOne() {
        assertTrue(Catalog.anyAlbum(listOf(album("x", "Play", "Moby"), album("y", "Other", "Someone")), "Moby!")!!.id == "x")
    }
}
