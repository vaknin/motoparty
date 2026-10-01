package com.kivan.motoparty.voicecmd

import com.kivan.motoparty.core.Interpretation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The interpreter on Gemini's Interactions API, called the way `~/Projects/capture` calls it: plain
 * OkHttp, no Google SDK, the answer held to a JSON schema. `tools/interpret/smoke.sh` sends the
 * same request from the laptop; keep the two alike. One instance asks one [model]; [FirstAnswer]
 * races the [MODELS].
 */
class CloudGemini(
    http: OkHttpClient,
    private val model: String,
    private val apiKey: String,
    private val prompt: String,
    schema: String,
    private val clock: () -> Long,
    private val guard: RateGuard = RateGuard(),
) : Interpreter {
    /** The phases of the call in flight (calls are one at a time), for the log line of a timeout. */
    @Volatile private var phases: Phases? = null
    private val http = http.newBuilder()
        // Just inside [FirstAnswer]'s limit, so a timeout is logged with its phases.
        .callTimeout(Interpretation.INTERPRET_TIMEOUT_MS - 100, TimeUnit.MILLISECONDS)
        .eventListenerFactory { Phases().also { phases = it } }
        .build()
    private val schema = Json.parseToJsonElement(schema)

    override suspend fun interpret(window: JsonObject): Interpreter.Answer {
        if (!guard.tryAcquire(clock())) return Interpreter.Failed("rate limited")
        val body = requestBody(model, prompt, schema, window)
        return try {
            runInterruptible(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("x-goog-api-key", apiKey)
                    .post(body.toRequestBody(jsonType))
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (response.code == 429) {
                        val hold = holdMs(text)
                        guard.hold(clock(), hold)
                        return@runInterruptible Interpreter.Failed("HTTP 429, holding ${hold / 1000} s")
                    }
                    if (response.code != 200) Interpreter.Failed("HTTP ${response.code}")
                    else answerText(text)?.let { Interpreter.Text(it, by = model) } ?: Interpreter.Failed("unreadable answer")
                }
            }
        } catch (_: InterruptedIOException) {
            // OkHttp's call timeout (and its socket timeouts). Where the time went, for the log.
            Interpreter.Failed("timeout" + (phases?.summary()?.let { " ($it)" } ?: ""))
        } catch (e: IOException) {
            Interpreter.Failed("no network (${e.javaClass.simpleName})")
        }
    }

    /**
     * Milliseconds from the call's start to each step it reached, e.g. `dns 40 [ipv4], connected 90,
     * tls 160, sent 170, no answer`: a slow DNS or connect is the phone's network, a long wait after
     * `sent` is Gemini.
     */
    private class Phases : EventListener() {
        private val start = System.nanoTime()
        private val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        private fun mark(what: String) { steps += "$what ${(System.nanoTime() - start) / 1_000_000}" }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            val kinds = inetAddressList.map { if (it.address.size == 4) "ipv4" else "ipv6" }.distinct()
            mark("dns"); steps[steps.lastIndex] += " [${kinds.joinToString("+")}]"
        }
        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) = mark("connection")
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?, ioe: IOException) =
            mark("connect to ${inetSocketAddress.address?.hostAddress} failed")
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) = mark("tls")
        override fun requestBodyEnd(call: Call, byteCount: Long) = mark("sent")
        override fun responseHeadersStart(call: Call) = mark("answer")
        override fun responseHeadersEnd(call: Call, response: Response) = Unit
        fun summary(): String = synchronized(steps) { steps.joinToString(", ") }.ifEmpty { "nothing" }
    }

    companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        /**
         * Asked together, the first answer wins (LLM-COMMANDS.md "Root cause of the slow answers": the
         * wait is Google's queue and varies call to call). The two stable Flash-Lites on 2026-09-30, the
         * only Flash line with a usable free tier; each has its own quota. 3.1 shuts down 2027-05-07.
         */
        val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
        /**
         * Not asked for a reply to a question: 3.1 turned road talk ("watch out for that truck") in
         * reply to "Which Moby album?" into playing an album, 2 of 3 times (2026-09-30). Gemma turned
         * "any" into the artist's songs; the question's own fallback is better (2026-10-01).
         */
        val NO_REPLIES = setOf("gemini-3.1-flash-lite", "gemma-4-26b-a4b-it")
        /**
         * Asked only when the [MODELS] cannot answer (all refused, or none answered by
         * [BACKUP_AFTER_MS]): about 1,000+ free a day and p50 1.2 s, but 30/33 right on `smoke.sh`
         * against the Flash-Lites' 49–53/53, with 9 of 53 refused for "high demand" (2026-10-01).
         */
        const val BACKUP_MODEL = "gemma-4-26b-a4b-it"
        /** Its answer (about 1.2 s) still lands inside [Interpretation.INTERPRET_TIMEOUT_MS]. */
        const val BACKUP_AFTER_MS = 4_000L
        /**
         * Its free tier allows 16,000 input tokens a minute: about 5 of our requests since the
         * context window grew them to about 3,000 tokens (2026-10-01; 8 at 1,750).
         */
        const val BACKUP_PER_MINUTE = 5
        /** Latency is what matters here: about 1 s a phrase with `minimal` (LLM-COMMANDS.md). */
        const val THINKING_LEVEL = "minimal"
        /** Free tier, per Cloud project (measured in capture, 2026-09-20): 15 a minute, 500 a day. */
        const val REQUESTS_PER_MINUTE = 15
        private const val MINUTE_HOLD_MS = 60_000L
        /** The daily quota resets at midnight Pacific; asking once an hour finds out. */
        private const val DAY_HOLD_MS = 3_600_000L

        private val jsonType = "application/json".toMediaType()

        /**
         * The request for the context [window] (PROTOCOL.md "Commands", *Voice actions*): the window's
         * JSON is the one text part, so the phrase in it travels as data, never as loose prompt text.
         */
        fun requestBody(model: String, prompt: String, schema: JsonElement, window: JsonObject): String {
            return buildJsonObject {
                put("model", model)
                put("system_instruction", prompt)
                putJsonArray("input") {
                    add(buildJsonObject { put("type", "text"); put("text", window.toString()) })
                }
                // No `temperature`: deprecated for Gemini 3.5 and later.
                putJsonObject("generation_config") {
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

        /**
         * How long to stop asking after a 429 with [body]. Google's own retry time (`retryDelay`, or "retry in 32s" in the message) wins
         * when it is there (the daily limit is a rolling window, so it too can end in seconds); else a body that
         * names a daily limit holds an hour and anything else a minute.
         */
        fun holdMs(body: String): Long {
            RETRY_DELAY.find(body)?.let { return ((it.groupValues[1].toDouble() + 1) * 1000).toLong().coerceIn(1_000L, DAY_HOLD_MS) }
            return if (body.contains("per day", ignoreCase = true)) DAY_HOLD_MS else MINUTE_HOLD_MS
        }

        /** `"retryDelay": "43s"` (RetryInfo) or the message's "Please retry in 32s" (seen 2026-09-30). */
        private val RETRY_DELAY = Regex("(?:\"retryDelay\"\\s*:\\s*\"|retry in )(\\d+(?:\\.\\d+)?)s\\b")

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
