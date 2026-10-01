package com.kivan.motoparty.music

import com.kivan.motoparty.core.Interpretation
import com.kivan.motoparty.core.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PROTOCOL.md "Commands", *Voice actions*: queue edits on window positions, undo, and what is said. */
class VoiceEditsTest {
    private fun t(id: String, artist: String = "a", ms: Long = 180_000) = Track(id, id.uppercase(), artist, durationMs = ms)
    private fun ids(q: List<Track>) = q.joinToString(",") { it.id }
    private val n1 = t("n1", "Moby")
    private val n2 = t("n2", "Coldplay")
    private val n3 = t("n3", "Moby & Gwen Stefani")
    private val n4 = t("n4", "Mobyland")
    private val n5 = t("n5", "Moby - Topic")
    private val upcoming = listOf(n1, n2, n3, n4, n5)
    private val snap = VoiceSnapshot(upcoming.map { it.id })

    @Test
    fun removeByPosition() {
        val e = VoiceEdits.removed(upcoming, snap, listOf(3, 1))
        assertEquals("n2,n4,n5", ids(e.upcoming))
        assertEquals("n3,n1", ids(e.tracks))
        assertEquals(0, e.missing)
    }

    /** Positions are the snapshot's: a track that moved meanwhile is still found, one that went is skipped. */
    @Test
    fun removeResolvesIdsNotIndexes() {
        val now = listOf(n2, n3, n5) // n1 and n4 went while the model was thinking
        val e = VoiceEdits.removed(now, snap, listOf(1, 3))
        assertEquals("n2,n5", ids(e.upcoming))
        assertEquals("n3", ids(e.tracks))
        assertEquals(1, e.missing)
    }

    @Test
    fun aTrackQueuedTwiceIsTakenWhereItWas() {
        val twice = listOf(n1, n2, n1, n3)
        val s = VoiceSnapshot(twice.map { it.id })
        assertEquals("n1,n2,n3", ids(VoiceEdits.removed(twice, s, listOf(3)).upcoming))
        assertEquals("n2,n1,n3", ids(VoiceEdits.removed(twice, s, listOf(1)).upcoming))
        // Both: each occurrence once.
        assertEquals("n2,n3", ids(VoiceEdits.removed(twice, s, listOf(1, 3)).upcoming))
    }

    @Test
    fun removeByArtistMatchesWholeWordsAlsoPastTheWindow() {
        val e = VoiceEdits.removedArtist(upcoming, "moby")
        assertEquals("n2,n4", ids(e.upcoming))
        assertEquals("n1,n3,n5", ids(e.tracks))
        assertEquals("n1,n2,n3,n4,n5", ids(VoiceEdits.removedArtist(upcoming, "cold play").upcoming))
    }

    @Test
    fun moveIsABlockAtItsFinalPosition() {
        assertEquals("n4,n1,n2,n3,n5", ids(VoiceEdits.moved(upcoming, snap, listOf(4), 1).upcoming))
        assertEquals("n3,n4,n1,n2,n5", ids(VoiceEdits.moved(upcoming, snap, listOf(3, 4), 1).upcoming))
        // In the order named.
        assertEquals("n2,n5,n1,n3,n4", ids(VoiceEdits.moved(upcoming, snap, listOf(5, 1), 2).upcoming))
        // Past the end = the end.
        assertEquals("n3,n4,n5,n1,n2", ids(VoiceEdits.moved(upcoming, snap, listOf(1, 2), 99).upcoming))
        val gone = VoiceEdits.moved(listOf(n1, n2), snap, listOf(4), 1)
        assertEquals(1, gone.missing)
        assertEquals("n1,n2", ids(gone.upcoming))
    }

    @Test
    fun locateFindsAJumpTarget() {
        assertEquals(1, VoiceEdits.locate(listOf(n1, n4), snap, 4))
        assertNull(VoiceEdits.locate(listOf(n1), snap, 4))
        assertNull(VoiceEdits.locate(upcoming, snap, 9))
    }

    @Test
    fun oneLinePerList() {
        val parts = listOf(
            VoiceEdits.removedLine(listOf(n1, n2)),
            VoiceEdits.movedLine(listOf(n3), 1, 3),
        )
        assertEquals("Removed 2 songs. Moved N3 to next" to false, VoiceEdits.line(parts))
        assertEquals("Removed N1 by Moby", VoiceEdits.removedLine(listOf(n1)).text)
        assertEquals("Moved 2 songs to the end", VoiceEdits.movedLine(listOf(n1, n2), 4, 5).text)
        assertEquals("Moved N1 to 3", VoiceEdits.movedLine(listOf(n1), 3, 5).text)
        assertEquals("Nothing to remove" to true, VoiceEdits.line(listOf(VoiceEdits.removedLine(emptyList()))))
        assertEquals(null, VoiceEdits.line(emptyList()))
        // A failure next to a success: the earcon stays ok.
        assertEquals(false, VoiceEdits.line(listOf(VoiceEdits.clearedLine(0), VoiceEdits.clearedLine(3)))!!.second)
    }

    @Test
    fun tellLines() {
        val porcelain = Track("p", "Porcelain", "Moby", "Play", 240_000)
        assertEquals("Porcelain by Moby", VoiceEdits.tellTrack(porcelain).text)
        assertEquals(VoiceEdits.Part("Nothing playing", failed = true), VoiceEdits.tellTrack(null))
        assertEquals("From Play", VoiceEdits.tellAlbum(porcelain).text)
        assertEquals("Album unknown", VoiceEdits.tellAlbum(n1).text)
        assertEquals("Next: N1 by Moby", VoiceEdits.tellNext(upcoming).text)
        assertEquals("Nothing after this", VoiceEdits.tellNext(emptyList()).text)
        assertEquals("Before this: N2 by Coldplay", VoiceEdits.tellPrevious(n2).text)
        assertEquals("Nothing before this", VoiceEdits.tellPrevious(null).text)
        // 5 × 3 min + the 1 min left of this one.
        assertEquals("5 songs left, about 16 minutes", VoiceEdits.tellRemaining(porcelain, 180_000, upcoming).text)
        assertEquals("1 song left, about 1 minute", VoiceEdits.tellRemaining(null, 0, listOf(t("x", ms = 40_000))).text)
    }

    @Test
    fun lastVoiceSaysHowLongAgoUntilTheUndoRunsOut() {
        assertEquals("removed N1 – Moby", VoiceEdits.named("removed", listOf(n1)))
        assertEquals("removed 4: N1 – Moby, N2 – Coldplay, N3 – Moby & Gwen Stefani, …", VoiceEdits.named("removed", upcoming.take(4)))
        assertEquals("cleared 3 (just now)", VoiceEdits.lastVoice("cleared 3", 1_000, 30_000))
        assertEquals("cleared 3 (3 min ago)", VoiceEdits.lastVoice("cleared 3", 0, 200_000))
        assertNull(VoiceEdits.lastVoice("cleared 3", 0, Interpretation.UNDO_MS + 1))
        assertNull(VoiceEdits.lastVoice(null, 0, 0))
    }

    @Test
    fun undoIsOneLevelForTenMinutes() {
        val undo = VoiceUndo()
        assertNull(undo.take(0))
        undo.keep(upcoming, RepeatMode.OFF, 1_000)
        val saved = undo.take(1_000 + Interpretation.UNDO_MS)!!
        assertEquals(upcoming, saved.upcoming)
        assertNull("an undo is not undone", undo.take(2_000))
        undo.keep(upcoming, RepeatMode.TRACK, 0)
        assertNull("too old", undo.take(Interpretation.UNDO_MS + 1))
        undo.keep(upcoming, RepeatMode.TRACK, 0)
        undo.forget()
        assertNull(undo.take(1))
    }

    @Test
    fun theCurrentTrackIsLeftOutOfWhatIsPutBack() {
        val saved = VoiceUndo.Saved(upcoming, RepeatMode.OFF, 0)
        assertEquals("n1,n3,n4,n5", ids(VoiceUndo.restored(saved, n2)))
        assertEquals(upcoming, VoiceUndo.restored(saved, null))
        // Removed two, then undone: two came back. Added two, then undone: none did.
        assertEquals(2, VoiceUndo.cameBack(upcoming, listOf(n1, n2, n3)))
        assertEquals(0, VoiceUndo.cameBack(listOf(n1), listOf(n1, n2, n3)))
        assertEquals("Put back 2 songs", VoiceEdits.undoLine(2).text)
        assertEquals("Undone", VoiceEdits.undoLine(0).text)
        assertEquals(VoiceEdits.Part("Nothing to undo", failed = true), VoiceEdits.undoLine(null))
    }
}
