package com.kivan.motoparty.voicecmd

import com.kivan.motoparty.core.Interpretation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * The interpreter on Gemini's Interactions API, called the way `~/Projects/capture` calls it: plain
 * OkHttp, no Google SDK, the answer held to a JSON schema. `tools/interpret/smoke.sh` sends the
 * same request from the laptop; keep the two alike.
 */
class CloudGemini(
    http: OkHttpClient,
    private val apiKey: String,
    private val prompt: String,
    schema: String,
    private val clock: () -> Long,
    private val guard: RateGuard = RateGuard(),
) : Interpreter {
    private val http = http.newBuilder().callTimeout(Interpretation.INTERPRET_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    private val schema = Json.parseToJsonElement(schema)

    override suspend fun interpret(phrase: String, lang: String, playing: String?, album: String?, upNext: List<String>, asked: Interpreter.Asked?): Interpreter.Answer {
        if (!guard.tryAcquire(clock())) return Interpreter.Failed("rate limited")
        val body = requestBody(prompt, schema, phrase, lang, playing, upNext, asked, album)
        return try {
            runInterruptible(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("x-goog-api-key", apiKey)
                    .post(body.toRequestBody(jsonType))
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (response.code == 429) guard.hold(clock(), holdMs(text))
                    if (response.code != 200) Interpreter.Failed("HTTP ${response.code}")
                    else answerText(text)?.let(Interpreter::Text) ?: Interpreter.Failed("unreadable answer")
                }
            }
        } catch (_: InterruptedIOException) {
            // OkHttp's call timeout (and its socket timeouts).
            Interpreter.Failed("timeout")
        } catch (e: IOException) {
            Interpreter.Failed("no network (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        /** The newest stable Flash-Lite on 2026-09-30; the only Flash line with a usable free tier. */
        const val MODEL = "gemini-3.5-flash-lite"
        /** Latency is what matters here: about 1 s a phrase with `minimal` (LLM-COMMANDS.md). */
        const val THINKING_LEVEL = "minimal"
        /** Free tier, per Cloud project (measured in capture, 2026-09-20): 15 a minute, 500 a day. */
        const val REQUESTS_PER_MINUTE = 15
        private const val MINUTE_HOLD_MS = 60_000L
        /** The daily quota resets at midnight Pacific; asking once an hour finds out. */
        private const val DAY_HOLD_MS = 3_600_000L

        private val jsonType = "application/json".toMediaType()

        fun requestBody(
            prompt: String, schema: JsonElement, phrase: String, lang: String, playing: String?, upNext: List<String>,
            asked: Interpreter.Asked? = null, album: String? = null,
        ): String {
            // The phrase travels as data inside a JSON object, never as loose prompt text.
            val input = buildJsonObject {
                put("phrase", phrase)
                put("lang", lang)
                put("playing", playing?.let(::JsonPrimitive) ?: JsonNull)
                if (album != null) put("album", album)
                put("upNext", buildJsonArray { upNext.forEach { add(JsonPrimitive(it)) } })
                // Only on the second turn: the phrase is then the reply to this question.
                if (asked != null) putJsonObject("asked") {
                    put("phrase", asked.phrase)
                    put("question", asked.question)
                }
            }
            return buildJsonObject {
                put("model", MODEL)
                put("system_instruction", prompt)
                putJsonArray("input") {
                    add(buildJsonObject { put("type", "text"); put("text", input.toString()) })
                }
                putJsonObject("generation_config") {
                    put("temperature", 0)
                    put("thinking_level", THINKING_LEVEL)
                }
                putJsonObject("response_format") {
                    put("type", "text")
                    put("mime_type", "application/json")
                    put("schema", schema)
                }
                // Nothing is kept on Google's side for a later turn.
                put("store", false)
            }.toString()
        }

        /** The model's text in a 200 body: every `text` part of every `model_output` step, joined. */
        fun answerText(body: String): String? {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            val steps = root["steps"] as? JsonArray ?: return null
            val parts = steps.filterIsInstance<JsonObject>().filter { it.string("type") == "model_output" }
                .flatMap { (it["content"] as? JsonArray).orEmpty() }
                .filterIsInstance<JsonObject>().filter { it.string("type") == "text" }
                .mapNotNull { it.string("text") }
            return parts.joinToString("").takeIf { it.isNotEmpty() }
        }

        /** How long to stop asking after a 429 with [body]: its text names the window that ran out. */
        fun holdMs(body: String): Long = if (body.contains("per day", ignoreCase = true)) DAY_HOLD_MS else MINUTE_HOLD_MS

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
