package com.kivan.motoparty.lyrics

import com.kivan.motoparty.core.Fixtures
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fixtures/lyrics.json: every section (`parse`, `timeline`, `served`) is consumed here. */
class LrcTest {
    private val fixture = Fixtures.load("lyrics.json").jsonObject
    private val lines = ListSerializer(LyricLine.serializer())

    @Test
    fun parseCases() {
        val cases = fixture["parse"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val o = c.jsonObject
            val expected = Json.decodeFromJsonElement(lines, o["lines"]!!)
            assertEquals(o["name"]!!.jsonPrimitive.content, expected, Lrc.parse(o["lrc"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun timelineSteps() {
        val t = fixture["timeline"]!!.jsonObject
        val case = fixture["parse"]!!.jsonArray[t["case"]!!.jsonPrimitive.int].jsonObject
        val parsed = Lrc.parse(case["lrc"]!!.jsonPrimitive.content)
        val steps = t["steps"]!!.jsonArray
        assertTrue(steps.isNotEmpty())
        for (s in steps) {
            val o = s.jsonObject
            val at = o["t"]!!.jsonPrimitive.long
            assertEquals("t=$at", LyricsPosition(o["line"]!!.jsonPrimitive.int, o["sung"]!!.jsonPrimitive.int), LyricsPosition.at(parsed, at))
        }
    }

    @Test
    fun servedBodyDecodesAndRoundTrips() {
        val text = fixture["served"]!!.toString()
        val body = LyricsBody.decode(text)
        assertEquals("abcDEF_-123", body.id)
        assertEquals("lrclib", body.source)
        assertEquals(Lrc.parse(fixture["parse"]!!.jsonArray[0].jsonObject["lrc"]!!.jsonPrimitive.content), body.lines)
        assertEquals(body, LyricsBody.decode(body.encode().toString(Charsets.UTF_8)))
    }

    @Test
    fun stampsCountOnlyFromTheFirstCharacter() {
        assertEquals(emptyList<LyricLine>(), Lrc.parse(" [00:01.00]Indented\n\t[00:02]Tabbed"))
        // A stamp in the middle is text.
        assertEquals("a [00:03] b", Lrc.parse("[00:01]a [00:03] b").single().text)
        // Non-ASCII digits are not digits here.
        assertEquals(emptyList<LyricLine>(), Lrc.parse("[٠٠:٠١]Arabic-Indic"))
    }

    @Test
    fun plausibilityFlagsRushedSyncs() {
        val good = Lrc.parse((0 until 10).joinToString("\n") { "[00:${"%02d".format(it * 4)}.00]a line long enough to judge" })
        val rushed = Lrc.parse((0 until 10).joinToString("\n") { "[00:0$it.50]a line long enough to judge" })
        assertEquals(1.0, Lrc.plausibility(good), 0.0)
        assertEquals(0.0, Lrc.plausibility(rushed), 0.0)
    }
}
