package com.kivan.motoparty.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationTest {
    @Test
    fun fixtureCases() {
        val root = Fixtures.load("interpret.json").jsonObject
        assertEquals(Interpretation.INTERPRET_TIMEOUT_MS, root["interpretTimeoutMs"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.INTERPRET_UP_NEXT.toLong(), root["interpretUpNext"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.ASK_MAX_CHARS.toLong(), root["askMaxChars"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.ANSWER_MS, root["answerMs"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.ANSWER_GRACE_MS, root["answerGraceMs"]!!.jsonPrimitive.long)
        assertEquals(CommandParser.QUEUE_MAX_COUNT.toLong(), root["queueMaxCount"]!!.jsonPrimitive.long)
        val cases = root["cases"]!!.jsonArray
        assertTrue("interpret.json has cases", cases.isNotEmpty())
        for (c in cases) {
            val answer = c.jsonObject["answer"]!!.jsonPrimitive.content
            val expected = when (val e = c.jsonObject["expect"]!!) {
                is JsonNull -> null
                is JsonObject -> Interpretation.Ask(
                    e["ask"]!!.jsonPrimitive.content,
                    e["fallback"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                )
                else -> Interpretation.Do(e.jsonPrimitive.content)
            }
            assertEquals(answer, expected, Interpretation.outcome(answer))
        }
    }

    /** Whatever comes out is a command the grammar parses: it is executed through the parser. */
    @Test
    fun everyCommandTextParses() {
        for (c in Fixtures.load("interpret.json").jsonObject["cases"]!!.jsonArray) {
            val text = when (val o = Interpretation.outcome(c.jsonObject["answer"]!!.jsonPrimitive.content)) {
                is Interpretation.Do -> o.text
                is Interpretation.Ask -> o.fallback
                null -> null
            } ?: continue
            assertNotEquals(text, Command.Unknown, CommandParser.parse(text))
        }
    }
}
