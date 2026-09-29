package com.kivan.motoparty.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun fixtureCases() {
        val cases = Fixtures.load("wake.json").jsonObject["cases"]!!.jsonArray
        assertTrue("wake.json has cases", cases.isNotEmpty())
        for (c in cases) {
            val text = c.jsonObject["text"]!!.jsonPrimitive.content
            val e = c.jsonObject["expect"]!!
            val expected = if (e is JsonNull) null else e.jsonPrimitive.content
            assertEquals("\"$text\"", expected, WakeWord.commandText(text))
        }
    }

    /** The text after the wake word goes straight to the parser, which must understand it. */
    @Test
    fun commandTextParses() {
        assertEquals(Command.Play(Command.Kind.SONG, "pink floyd"), CommandParser.parse(WakeWord.commandText("Moto party, play Pink Floyd")!!))
        assertEquals(Command.End, CommandParser.parse(WakeWord.commandText("OK motoparty, over")!!))
    }
}
