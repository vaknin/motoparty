package com.kivan.motoparty.music

import com.kivan.motoparty.core.EnqueueMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueEditsTest {
    private fun t(id: String) = Track(id, id, "a", durationMs = 1000)
    private fun ids(q: List<Track>) = q.joinToString(",") { it.id }
    private val q = listOf(t("p"), t("c"), t("n1"), t("n2"))

    @Test
    fun nextGoesRightAfterTheCurrentTrack() {
        assertEquals("p,c,x,y,n1,n2", ids(QueueEdits.inserted(q, 1, EnqueueMode.NEXT, listOf(t("x"), t("y")))))
    }

    @Test
    fun endAppends() {
        assertEquals("p,c,n1,n2,x", ids(QueueEdits.inserted(q, 1, EnqueueMode.END, listOf(t("x")))))
    }

    @Test
    fun insertKeepsAtMost200Upcoming() {
        val many = (1..300).map { t("m$it") }
        val out = QueueEdits.inserted(q, 1, EnqueueMode.NEXT, many)
        assertEquals(2 + QueueEdits.MAX_UPCOMING, out.size)
        assertEquals("m1", out[2].id)
        // Appending past the cap drops the new tracks, not the ones already queued.
        val end = QueueEdits.inserted(q, 1, EnqueueMode.END, many)
        assertEquals("n1,n2,m1", ids(end.subList(2, 5)))
        assertEquals(2 + QueueEdits.MAX_UPCOMING, end.size)
    }

    @Test
    fun cappedCountsFromTheStartTrack() {
        val many = (1..300).map { t("m$it") }
        assertEquals(1 + QueueEdits.MAX_UPCOMING, QueueEdits.capped(many, 0).size)
        assertEquals(11 + QueueEdits.MAX_UPCOMING, QueueEdits.capped(many, 10).size)
        assertEquals(QueueEdits.MAX_UPCOMING, QueueEdits.capped(many, -1).size)
    }

    @Test
    fun atFindsUpcomingByIndexAndId() {
        assertEquals(3, QueueEdits.at(q, 1, 1, "n2"))
        assertEquals(2, QueueEdits.at(q, 1, 0, "n1"))
        // With nothing current (index -1) upcoming is the whole queue.
        assertEquals(0, QueueEdits.at(q, -1, 0, "p"))
    }

    @Test
    fun staleOrOutOfRangeEditsAreRefused() {
        assertNull(QueueEdits.at(q, 1, 0, "n2"))
        assertNull(QueueEdits.at(q, 1, 2, "n2"))
        assertNull(QueueEdits.at(q, 1, -1, "c"))
    }
}
