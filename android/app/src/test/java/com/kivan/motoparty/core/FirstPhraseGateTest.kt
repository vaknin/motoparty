package com.kivan.motoparty.core

import com.kivan.motoparty.core.FirstPhraseGate.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md "Commands", *The first phrase decides* and *Solo talk*. */
class FirstPhraseGateTest {
    @Test
    fun fixtureCases() {
        val root = Fixtures.load("first_phrase.json").jsonObject
        assertEquals(FirstPhraseGate.FIRST_PHRASE_MS, root["firstPhraseMs"]!!.jsonPrimitive.long)
        assertEquals(Interpretation.ANSWER_MS, root["answerMs"]!!.jsonPrimitive.long)
        val cases = root["cases"]!!.jsonArray
        assertTrue("first_phrase.json has cases", cases.isNotEmpty())
        for (c in cases) {
            val name = c.jsonObject["name"]!!.jsonPrimitive.content
            val role = Role.valueOf(c.jsonObject["role"]!!.jsonPrimitive.content.uppercase())
            val phrases = c.jsonObject["phrases"]!!.jsonArray
            val expect = c.jsonObject["expect"]!!.jsonArray
            assertEquals(name, phrases.size, expect.size)
            val gate = FirstPhraseGate()
            gate.open(role, interpret = c.jsonObject["interpret"]?.jsonPrimitive?.boolean ?: false)
            gate.live(0)
            var askAt = c.jsonObject["askAtMs"]?.jsonPrimitive?.long
            phrases.forEachIndexed { i, p ->
                val text = p.jsonObject["text"]!!.jsonPrimitive.content
                val at = p.jsonObject["atMs"]!!.jsonPrimitive.long
                askAt?.takeIf { it <= at }?.let {
                    gate.ask(it)
                    askAt = null
                }
                val e = expect[i]
                val expected = if (e is JsonNull) null else e.jsonPrimitive.content
                assertEquals("$name: \"$text\" at $at", expected, gate.onPhrase(text, at))
            }
        }
    }

    @Test
    fun spentAfterTheFirstPhraseOrTheWindow() {
        val gate = FirstPhraseGate()
        gate.open(Role.OPENER)
        gate.live(1_000)
        assertFalse(gate.isSpent(9_000))
        assertTrue(gate.isSpent(9_001))
        // An empty phrase does not use it; a conversation one does.
        assertNull(gate.onPhrase("...", 2_000))
        assertFalse(gate.isSpent(2_000))
        assertNull(gate.onPhrase("look at that bike", 3_000))
        assertTrue(gate.isSpent(3_000))
    }

    @Test
    fun beforeTheLiveEarconTheWindowHasNotStarted() {
        val gate = FirstPhraseGate()
        gate.open(Role.OPENER)
        assertFalse(gate.isSpent(60_000))
        assertEquals("next", gate.onPhrase("Next", 60_000))
        // The first live earcon of the talk is the one that counts.
        gate.open(Role.OPENER)
        gate.live(0)
        gate.live(5_000)
        assertNull(gate.onPhrase("next", 8_001))
    }

    @Test
    fun aQuestionOpensTheGateForOneReply() {
        val gate = FirstPhraseGate()
        gate.open(Role.OPENER, interpret = true)
        gate.live(0)
        assertEquals("play an album by moby", gate.onPhrase("play an album by Moby", 2_000))
        assertTrue(gate.isSpent(2_000))
        gate.ask(3_000)
        assertFalse(gate.isSpent(13_000))
        assertTrue(gate.isSpent(13_001))
        assertEquals("any", gate.onPhrase("Any.", 6_000))
        assertTrue(gate.isSpent(6_000))
        assertNull(gate.onPhrase("next", 7_000))
        // A new talk forgets the question.
        gate.ask(8_000)
        gate.open(Role.OPENER)
        gate.live(20_000)
        assertNull(gate.onPhrase("whatever you like", 21_000))
    }

    @Test
    fun roles() {
        val gate = FirstPhraseGate()
        gate.open(Role.OTHER)
        assertTrue(gate.isSpent(0))
        gate.open(Role.SOLO)
        gate.live(0)
        assertFalse(gate.isSpent(1_000_000))
        assertEquals("next", gate.onPhrase("next", 1_000_000))
    }

    @Test
    fun openStartsANewTalk() {
        val gate = FirstPhraseGate()
        gate.open(Role.OPENER)
        gate.live(0)
        assertEquals("pause", gate.onPhrase("pause", 1_000))
        gate.open(Role.OPENER)
        gate.live(20_000)
        assertEquals("next", gate.onPhrase("next", 21_000))
    }
}
