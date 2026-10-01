package com.kivan.motoparty.voicecmd

import com.kivan.motoparty.core.RepeatMode
import com.kivan.motoparty.music.Track
import com.kivan.motoparty.music.VoiceWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudGeminiTest {
    private val schema = Json.parseToJsonElement("""{"type":"object"}""")

    private fun body(window: kotlinx.serialization.json.JsonObject) =
        Json.parseToJsonElement(CloudGemini.requestBody("gemini-x", "PROMPT", schema, window)).jsonObject

    private fun input(window: kotlinx.serialization.json.JsonObject) =
        Json.parseToJsonElement(body(window)["input"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject

    @Test
    fun requestBodyIsTheInteractionsShape() {
        val porcelain = Track("p1", "Porcelain", "Moby", "Play", 241_000)
        val window = VoiceWindow.build(
            "put on \"moby\"", "en-US", porcelain, 74_500, RepeatMode.OFF,
            listOf(Track("n1", "Natural Blues", "Moby", durationMs = 0)), emptyList(), null,
        ).input
        val body = body(window)
        assertEquals("gemini-x", body["model"]!!.jsonPrimitive.content)
        assertEquals("PROMPT", body["system_instruction"]!!.jsonPrimitive.content)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        val config = body["generation_config"]!!.jsonObject
        assertNull(config["temperature"])
        assertEquals("minimal", config["thinking_level"]!!.jsonPrimitive.content)
        val format = body["response_format"]!!.jsonObject
        assertEquals("text", format["type"]!!.jsonPrimitive.content)
        assertEquals("application/json", format["mime_type"]!!.jsonPrimitive.content)
        assertEquals(schema, format["schema"])
        assertEquals(setOf("model", "system_instruction", "input", "generation_config", "response_format", "store"), body.keys)
        // The window is the one JSON text part: quotes in the phrase cannot break out of the data.
        val part = body["input"]!!.jsonArray.single().jsonObject
        assertEquals("text", part["type"]!!.jsonPrimitive.content)
        val input = Json.parseToJsonElement(part["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals(window, input)
        assertEquals("put on \"moby\"", input["phrase"]!!.jsonPrimitive.content)
        assertEquals("Porcelain", input["playing"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertNull(input["asked"])
    }

    @Test
    fun aReplyCarriesTheQuestion() {
        val asked = VoiceWindow.Asked("play an album by moby", "Which Moby album?")
        val input = input(VoiceWindow.build("any", "en-US", null, 0, RepeatMode.OFF, emptyList(), emptyList(), null, asked).input)
        assertEquals("any", input["phrase"]!!.jsonPrimitive.content)
        assertEquals("play an album by moby", input["asked"]!!.jsonObject["phrase"]!!.jsonPrimitive.content)
        assertEquals("Which Moby album?", input["asked"]!!.jsonObject["question"]!!.jsonPrimitive.content)
    }

    @Test
    fun nothingPlayingIsNull() {
        val input = input(VoiceWindow.build("next one", "en-US", null, 0, RepeatMode.OFF, emptyList(), emptyList(), null).input)
        assertEquals(JsonNull, input["playing"])
        assertTrue(input["upNext"]!!.jsonArray.isEmpty())
    }

    @Test
    fun answerTextJoinsTheModelOutputTextParts() {
        val body = """{"status":"completed","steps":[
            {"type":"thought","content":[{"type":"text","text":"hm"}]},
            {"type":"model_output","content":[{"type":"text","text":"{\"actions\":"},{"type":"image"},{"type":"text","text":"[]}"}]}
        ]}"""
        assertEquals("""{"actions":[]}""", CloudGemini.answerText(body))
    }

    @Test
    fun answerTextOfAnythingElseIsNull() {
        assertNull(CloudGemini.answerText(""))
        assertNull(CloudGemini.answerText("not json"))
        assertNull(CloudGemini.answerText("[]"))
        assertNull(CloudGemini.answerText("""{"status":"completed"}"""))
        assertNull(CloudGemini.answerText("""{"steps":[{"type":"model_output","content":[]}]}"""))
    }

    @Test
    fun holdIsLongerWhenTheDayRanOut() {
        assertEquals(60_000L, CloudGemini.holdMs("limit: 15 requests per minute on Free Tier"))
        assertEquals(3_600_000L, CloudGemini.holdMs("limit: 500 requests per day on Free Tier"))
        // Google's RetryInfo wins, plus a second of margin, even next to a daily limit's name.
        assertEquals(44_000L, CloudGemini.holdMs("""{"error": {"details": [{"retryDelay": "43s"}], "message": "… per day …"}}"""))
        assertEquals(13_500L, CloudGemini.holdMs("""{"retryDelay":"12.5s"}"""))
        // The daily limit's own words (2026-09-30): a rolling window, retry in seconds.
        assertEquals(33_000L, CloudGemini.holdMs("Rate limit exceeded for model gemini-3.5-flash-lite (limit: 500 requests per day on Free Tier). Please retry in 32s or upgrade your tier"))
    }

    @Test
    fun rateGuardAllowsFifteenAMinute() {
        val guard = RateGuard(perMinute = 15)
        repeat(15) { assertTrue(guard.tryAcquire(1_000L + it)) }
        assertFalse(guard.tryAcquire(2_000))
        assertFalse(guard.tryAcquire(60_999))
        // The first one is a minute old.
        assertTrue(guard.tryAcquire(61_000))
        assertFalse(guard.tryAcquire(61_000))
    }

    @Test
    fun rateGuardHoldsAfterA429() {
        val guard = RateGuard(perMinute = 15)
        guard.hold(10_000, 60_000)
        assertFalse(guard.tryAcquire(69_999))
        assertTrue(guard.tryAcquire(70_000))
    }
}
