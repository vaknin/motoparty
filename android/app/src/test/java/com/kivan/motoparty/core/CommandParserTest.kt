package com.kivan.motoparty.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    @Test
    fun fixtureCases() {
        val cases = Fixtures.load("commands.json").jsonObject["cases"]!!.jsonArray
        for (c in cases) {
            val text = c.jsonObject["text"]!!.jsonPrimitive.content
            val e = c.jsonObject["expect"]!!.jsonObject
            val expected: Command = when (val action = e["action"]!!.jsonPrimitive.content) {
                "play" -> Command.Play(
                    Command.Kind.entries.first { it.word == e["kind"]!!.jsonPrimitive.content },
                    e["query"]!!.jsonPrimitive.content,
                )
                "pause" -> Command.Pause
                "resume" -> Command.Resume
                "next" -> Command.Next
                "previous" -> Command.Previous
                "volumeUp" -> Command.VolumeUp
                "volumeDown" -> Command.VolumeDown
                "end" -> Command.End
                "nowplaying" -> Command.NowPlaying
                "shuffle" -> Command.Shuffle
                "unknown" -> Command.Unknown
                else -> error("unexpected action $action")
            }
            assertEquals("\"$text\"", expected, CommandParser.parse(text))
        }
    }

    @Test
    fun nonLatinLettersSurviveNormalisation() {
        assertEquals(Command.Play(Command.Kind.SONG, "שיר אהבה"), CommandParser.parse("Play שיר אהבה!"))
    }
}
