package com.kivan.motoparty.music

import com.kivan.motoparty.core.Command
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceQueueTest {
    private fun t(id: String, title: String = id) = Track(id, title, "Moby", durationMs = 1000)
    private fun q(where: Command.Where = Command.Where.END, count: Int? = null, kind: Command.Kind? = Command.Kind.ARTIST) =
        Command.Queue(where, count, kind, if (kind == null) "" else "moby")
    private val album = listOf(t("a", "Honey"), t("b", "Find My Baby"), t("c", "Porcelain"), t("d", "Why Does My Heart"), t("e", "South Side"))
    private fun ids(list: List<Track>) = list.joinToString("") { it.id }

    @Test
    fun theRestOfTheAlbumIsWhatComesAfterTheCurrentTrack() {
        val album = q(kind = Command.Kind.ALBUM)
        assertEquals("de", ids(VoiceQueue.pick(album, this.album, this.album[2], emptyList())))
        // The current track came from a song search: another id, the same title.
        assertEquals("de", ids(VoiceQueue.pick(album, this.album, t("x", "porcelain!"), emptyList())))
        // Not on the album: all of it.
        assertEquals("abcde", ids(VoiceQueue.pick(album, this.album, t("x", "Flower"), emptyList())))
        assertEquals("abcde", ids(VoiceQueue.pick(album, this.album, null, emptyList())))
        // Only an album has a "rest".
        assertEquals("abde", ids(VoiceQueue.pick(q(), this.album, this.album[2], emptyList())))
    }

    @Test
    fun whatIsCurrentOrUpcomingIsNotAddedAgain() {
        assertEquals("ace", ids(VoiceQueue.pick(q(), album + album, null, listOf(album[1], album[3]))))
        // `instead` replaces the upcoming tracks, so they may come back.
        assertEquals("bcde", ids(VoiceQueue.pick(q(Command.Where.INSTEAD), album, album[0], listOf(album[1], album[3]))))
    }

    @Test
    fun theCountLimitsAndSimilarHasItsOwnDefault() {
        assertEquals("bc", ids(VoiceQueue.pick(q(count = 2), album, album[0], emptyList())))
        val many = (1..40).map { t("s$it") }
        assertEquals(VoiceQueue.QUEUE_SIMILAR, VoiceQueue.pick(q(kind = null), many, null, emptyList()).size)
        assertEquals(40, VoiceQueue.pick(q(), many, null, emptyList()).size)
        assertEquals(3, VoiceQueue.pick(q(count = 3, kind = null), many, null, emptyList()).size)
    }

    @Test
    fun replies() {
        assertEquals("Added Honey by Moby", VoiceQueue.reply(Command.Where.END, album.take(1)))
        assertEquals("Added 5 songs", VoiceQueue.reply(Command.Where.INSTEAD, album))
        assertEquals("Next: Honey by Moby", VoiceQueue.reply(Command.Where.NEXT, album.take(1)))
        assertEquals("Next: 2 songs", VoiceQueue.reply(Command.Where.NEXT, album.take(2)))
    }
}
