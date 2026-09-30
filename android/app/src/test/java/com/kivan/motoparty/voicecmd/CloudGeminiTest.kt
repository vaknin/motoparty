package com.kivan.motoparty.voicecmd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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

    @Test
    fun requestBodyIsTheInteractionsShape() {
        val body = Json.parseToJsonElement(
            CloudGemini.requestBody("PROMPT", schema, "put on \"moby\"", "en-US", "Honey – Moby", listOf("Porcelain")),
        ).jsonObject
        assertEquals(CloudGemini.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("PROMPT", body["system_instruction"]!!.jsonPrimitive.content)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        val config = body["generation_config"]!!.jsonObject
        assertEquals(0, config["temperature"]!!.jsonPrimitive.int)
        assertEquals("minimal", config["thinking_level"]!!.jsonPrimitive.content)
        val format = body["response_format"]!!.jsonObject
        assertEquals("application/json", format["mime_type"]!!.jsonPrimitive.content)
        assertEquals(schema, format["schema"])
        // The phrase is one JSON text part: quotes in it cannot break out of the data.
        val part = body["input"]!!.jsonArray.single().jsonObject
        assertEquals("text", part["type"]!!.jsonPrimitive.content)
        val input = Json.parseToJsonElement(part["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals("put on \"moby\"", input["phrase"]!!.jsonPrimitive.content)
        assertEquals("en-US", input["lang"]!!.jsonPrimitive.content)
        assertEquals("Honey – Moby", input["playing"]!!.jsonPrimitive.content)
        assertEquals("Porcelain", input["upNext"]!!.jsonArray.single().jsonPrimitive.content)
        assertNull(input["asked"])
    }

    @Test
    fun aReplyCarriesTheQuestion() {
        val asked = Interpreter.Asked("play an album by moby", "Which Moby album?")
        val body = Json.parseToJsonElement(CloudGemini.requestBody("P", schema, "any", "en-US", null, emptyList(), asked)).jsonObject
        val input = Json.parseToJsonElement(body["input"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals("any", input["phrase"]!!.jsonPrimitive.content)
        assertEquals("play an album by moby", input["asked"]!!.jsonObject["phrase"]!!.jsonPrimitive.content)
        assertEquals("Which Moby album?", input["asked"]!!.jsonObject["question"]!!.jsonPrimitive.content)
    }

    @Test
    fun nothingPlayingIsNull() {
        val body = Json.parseToJsonElement(CloudGemini.requestBody("P", schema, "next one", "en-US", null, emptyList())).jsonObject
        val input = Json.parseToJsonElement(body["input"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals(JsonNull, input["playing"])
        assertTrue(input["upNext"]!!.jsonArray.isEmpty())
    }

    @Test
    fun answerTextJoinsTheModelOutputTextParts() {
        val body = """{"status":"completed","steps":[
            {"type":"thought","content":[{"type":"text","text":"hm"}]},
            {"type":"model_output","content":[{"type":"text","text":"{\"action\":"},{"type":"image"},{"type":"text","text":"\"next\"}"}]}
        ]}"""
        assertEquals("""{"action":"next"}""", CloudGemini.answerText(body))
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
