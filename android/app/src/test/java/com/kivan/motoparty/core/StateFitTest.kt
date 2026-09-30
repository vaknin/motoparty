package com.kivan.motoparty.core

import com.kivan.motoparty.link.StateFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md `state.queue`: `art` leaves every item when `state` would pass 48 KiB. */
class StateFitTest {
    private fun item(i: Int, art: String?) = QueueItem("id$i", "Title $i", "Artist $i", durationMs = 200_000L + i, art = art)
    private fun state(n: Int, art: String?) = State(
        talk = false,
        music = MusicState("cur", "T", "A", true, 0, 0, 1000, art = "https://img/cur"),
        queue = (0 until n).map { item(it, art) },
    )
    private fun size(s: State) = Codec.encode(s).toByteArray().size

    @Test
    fun smallStateKeepsItsArt() {
        val s = state(20, "https://img/x")
        assertSame(s, StateFit.fit(s))
    }

    @Test
    fun over48KiBEveryItemLosesArtAndNothingElse() {
        val s = state(200, "https://i.ytimg.com/vi/" + "x".repeat(300))
        assertTrue(size(s) > StateFit.STATE_ART_LIMIT)
        val fitted = StateFit.fit(s)
        assertTrue(fitted.queue.all { it.art == null })
        assertEquals(s.queue.map { it.copy(art = null) }, fitted.queue)
        assertEquals("the current track keeps its cover", s.music, fitted.music)
        assertTrue(size(fitted) <= StateFit.STATE_ART_LIMIT)
        assertTrue(Codec.fits(fitted))
    }

    @Test
    fun theLimitItselfIsAllowed() {
        val s = state(30, "https://img/x")
        val n = size(s)
        assertSame(s, StateFit.fit(s, limit = n))
        assertTrue(StateFit.fit(s, limit = n - 1).queue.all { it.art == null })
    }

    @Test
    fun aStateWithoutArtIsLeftAlone() {
        val s = state(200, null)
        assertSame(s, StateFit.fit(s, limit = 10))
    }
}
