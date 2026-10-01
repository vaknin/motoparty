package com.kivan.motoparty.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationTest {
    private val root = Fixtures.load("interpret.json").jsonObject

    @Test
    fun constantsMatchTheFixture() {
        assertEquals(Interpretation.INTERPRET_TIMEOUT_MS, root["interpretTimeoutMs"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.INTERPRET_UP_NEXT.toLong(), root["interpretUpNext"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.INTERPRET_PLAYED.toLong(), root["interpretPlayed"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.MAX_ACTIONS.toLong(), root["maxActions"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.UNDO_MS, root["undoMs"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.ASK_MAX_CHARS.toLong(), root["askMaxChars"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.ANSWER_MS, root["answerMs"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.ANSWER_GRACE_MS, root["answerGraceMs"]!!.jsonPrimitive.long)
        assertEquals(CommandParser.QUEUE_MAX_COUNT.toLong(), root["queueMaxCount"]!!.jsonPrimitive.long)
    }

    /** Every case, compared in the fixture's canonical form. */
    @Test
    fun fixtureCases() {
        val cases = root["cases"]!!.jsonArray
        assertTrue("interpret.json has cases", cases.size > 100)
        for (c in cases) {
            val answer = c.jsonObject["answer"]!!.jsonPrimitive.content
            val window = c.jsonObject["window"]?.jsonObject
            val outcome = Interpretation.outcome(
                answer,
                upNext = window?.get("upNext")?.jsonPrimitive?.int ?: Interpretation.INTERPRET_UP_NEXT,
                played = window?.get("played")?.jsonPrimitive?.int ?: Interpretation.INTERPRET_PLAYED,
            )
            assertEquals(answer, c.jsonObject["expect"]!!, canonical(outcome))
        }
    }

    /** The outcome as the fixture writes it: null, a list of actions, or {ask, fallback}. */
    private fun canonical(o: Interpretation.Outcome?): JsonElement = when (o) {
        null -> JsonNull
        is Interpretation.Do -> JsonArray(o.actions.map { it.canonical() })
        is Interpretation.Ask -> JsonObject(
            mapOf("ask" to kotlinx.serialization.json.JsonPrimitive(o.question), "fallback" to JsonArray(o.fallback.map { it.canonical() })),
        )
    }

    /** A list never holds `none` or `ask`, and never more than four. */
    @Test
    fun aDoIsNeverEmptyNorLong() {
        for (c in root["cases"]!!.jsonArray) {
            val o = Interpretation.outcome(c.jsonObject["answer"]!!.jsonPrimitive.content) as? Interpretation.Do ?: continue
            assertTrue(o.actions.size in 1..Interpretation.MAX_ACTIONS)
        }
    }

    /** The window's sizes bound the positions: the same answer with a smaller window is dropped. */
    @Test
    fun positionsAreCheckedAgainstTheWindow() {
        val answer = """{"actions":[{"type":"jump","at":-5},{"type":"remove","at":[25]}]}"""
        assertEquals(
            Interpretation.Do(listOf(VoiceAction.Jump(-5), VoiceAction.Remove(at = listOf(25)))),
            Interpretation.outcome(answer),
        )
        assertEquals(null, Interpretation.outcome(answer, upNext = 24, played = 4))
    }
}
