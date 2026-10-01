package com.kivan.motoparty.music

import com.kivan.motoparty.core.RepeatMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** PROTOCOL.md "Commands", *Voice actions*: the context window, exactly as the spec shows it. */
class VoiceWindowTest {
    private val porcelain = Track("p1", "Porcelain", "Moby", "Play", 241_000)

    @Test
    fun theSpecsExample() {
        val upcoming = listOf(
            Track("n1", "Natural Blues", "Moby", durationMs = 1),
            Track("n2", "Why Does My Heart Feel So Bad?", "Moby", durationMs = 1),
            Track("n3", "Yellow", "Coldplay", durationMs = 1),
        ) + (4..14).map { Track("x$it", "X$it", "", durationMs = 1) }
        // The History store's list starts with the current track once it has played.
        val history = listOf(porcelain, Track("t1", "Teardrop", "Massive Attack", durationMs = 1), Track("t2", "Angel", "Massive Attack", durationMs = 1))
        val w = VoiceWindow.build(
            "drop the next two and play yellow after this", "en-US", porcelain, 74_900, RepeatMode.OFF,
            upcoming, history, "removed Clocks – Coldplay (3 min ago)",
        )
        val expected = Json.parseToJsonElement(
            """{"phrase": "drop the next two and play yellow after this", "lang": "en-US",
             "playing": {"title": "Porcelain", "artist": "Moby", "album": "Play", "atS": 74, "lengthS": 241},
             "repeat": "off",
             "upNext": ["1. Natural Blues – Moby", "2. Why Does My Heart Feel So Bad? – Moby", "3. Yellow – Coldplay",
               "4. X4", "5. X5", "6. X6", "7. X7", "8. X8", "9. X9", "10. X10", "11. X11", "12. X12", "13. X13", "14. X14"],
             "queueLength": 14,
             "played": ["-1. Teardrop – Massive Attack", "-2. Angel – Massive Attack"],
             "lastVoice": "removed Clocks – Coldplay (3 min ago)"}""",
        )
        assertEquals(expected, w.input)
        assertEquals(upcoming.map { it.id }, w.snapshot.upNext)
        assertEquals(listOf("t1", "t2"), w.snapshot.played.map { it.id })
    }

    @Test
    fun theWindowIsCappedAndUnknownsAreLeftOut() {
        val upcoming = (1..30).map { Track("u$it", "U$it", "A", durationMs = 1) }
        val history = (1..9).map { Track("h$it", "H$it", "B", durationMs = 1) }
        val noAlbum = Track("c", "Song", "", durationMs = 0)
        val w = VoiceWindow.build(
            "any", "he-IL", noAlbum, 5_000, RepeatMode.QUEUE, upcoming, history, null,
            VoiceWindow.Asked("play an album by moby", "Which Moby album?"),
        )
        val expected = Json.parseToJsonElement(
            """{"phrase": "any", "lang": "he-IL", "playing": {"title": "Song", "artist": "", "atS": 5},
             "repeat": "queue",
             "upNext": [${(1..25).joinToString(", ") { "\"$it. U$it – A\"" }}],
             "queueLength": 30,
             "played": [${(1..5).joinToString(", ") { "\"-$it. H$it – B\"" }}],
             "lastVoice": null,
             "asked": {"phrase": "play an album by moby", "question": "Which Moby album?"}}""",
        )
        assertEquals(expected, w.input)
        assertEquals(25, w.snapshot.upNext.size)
        assertEquals(5, w.snapshot.played.size)
    }

    @Test
    fun nothingPlaying() {
        val w = VoiceWindow.build("play moby", "en-US", null, 0, RepeatMode.OFF, emptyList(), emptyList(), null)
        assertEquals(
            Json.parseToJsonElement(
                """{"phrase":"play moby","lang":"en-US","playing":null,"repeat":"off","upNext":[],"queueLength":0,"played":[],"lastVoice":null}""",
            ),
            w.input,
        )
    }
}
