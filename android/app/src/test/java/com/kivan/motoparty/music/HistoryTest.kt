package com.kivan.motoparty.music

import com.kivan.motoparty.core.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryTest {
    private fun t(id: String) = Track(id, "T $id", "A", durationMs = 1000, art = "art-$id")

    @Test
    fun searchesAreNewestFirstDeduplicatedAndCapped() {
        var h = History()
        for (i in 1..12) h = h.searched(SearchKind.SONGS, "q$i")
        assertEquals((12 downTo 3).map { "q$it" }, h.searches.map { it.query })
        // The same text again moves to the top with its new chip, once.
        h = h.searched(SearchKind.ALBUMS, "  Q5 ")
        assertEquals(RecentSearch(SearchKind.ALBUMS, "Q5"), h.searches.first())
        assertEquals(1, h.searches.count { it.query.equals("q5", ignoreCase = true) })
        assertEquals(History.MAX_SEARCHES, h.searches.size)
    }

    @Test
    fun blankOrUnknownSearchesAreNotKept() {
        val h = History()
        assertSame(h, h.searched(SearchKind.SONGS, "   "))
        assertSame(h, h.searched("videos", "x"))
        // Artists (2026-10-02) are a kind like the others.
        assertEquals(SearchKind.ARTISTS, h.searched(SearchKind.ARTISTS, "queen").searches.single().kind)
        assertEquals("a b", h.searched(SearchKind.SONGS, " a \n b ").searches.single().query)
    }

    @Test
    fun playedIsNewestFirstByIdAndCapped() {
        var h = History()
        for (i in 1..25) h = h.played(t("id$i"))
        assertEquals(History.MAX_PLAYED, h.played.size)
        assertEquals("id25", h.played.first().id)
        h = h.played(t("id10"))
        assertEquals(listOf("id10", "id25", "id24"), h.played.take(3).map { it.id })
        assertEquals(1, h.played.count { it.id == "id10" })
    }

    @Test
    fun clearingSearchesKeepsPlayed() {
        val h = History().searched(SearchKind.SONGS, "x").played(t("a")).withoutSearches()
        assertEquals(emptyList<RecentSearch>(), h.searches)
        assertEquals(listOf("a"), h.played.map { it.id })
    }

    @Test
    fun roundTripsAndSurvivesGarbage() {
        val h = History().searched(SearchKind.PLAYLISTS, "road trip").played(t("abc").copy(album = "X"))
        assertEquals(h, History.decode(History.encode(h)))
        assertEquals(History(), History.decode(null))
        assertEquals(History(), History.decode("{not json"))
        // Bad ids and kinds from an older or hand-edited file are dropped on the way in.
        val raw = """{"searches":[{"kind":"videos","query":"x"},{"kind":"songs","query":"ok"}],""" +
            """"played":[{"id":"../etc","title":"t","artist":"a","durationMs":1},{"id":"good","title":"t","artist":"a","durationMs":1}]}"""
        val d = History.decode(raw)
        assertEquals(listOf("ok"), d.searches.map { it.query })
        assertEquals(listOf("good"), d.played.map { it.id })
    }
}
