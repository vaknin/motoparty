package com.kivan.motoparty.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Fatal for the connection: oversize frame or invalid JSON. */
open class ProtocolException(message: String) : IOException(message)

/** A well-formed frame to drop and log (missing `t`, or a known type with bad fields). */
class MalformedMessageException(message: String) : ProtocolException(message)

/** JSON <-> [Message], and the u32 length-prefixed framing around it. */
object Codec {
    const val MAX_FRAME = 64 * 1024

    val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        // Absent optional fields are omitted, never null.
        explicitNulls = false
        encodeDefaults = true
    }

    private val serializers: Map<String, KSerializer<out Message>> = listOf(
        Hello.serializer(), Ping.serializer(), Pong.serializer(), TalkOpen.serializer(),
        TalkClose.serializer(), MusicLoad.serializer(), MusicReady.serializer(),
        MusicError.serializer(), MusicPlay.serializer(), MusicPause.serializer(),
        MusicStop.serializer(), MusicControl.serializer(), CommandText.serializer(),
        Announce.serializer(), State.serializer(), Bye.serializer(),
    ).associateBy { it.descriptor.serialName }

    fun encode(message: Message): String {
        require(message !is UnknownMessage) { "cannot encode an unknown message" }
        return json.encodeToString(Message.serializer(), message)
    }

    /**
     * Decodes one JSON object. Unknown `t` values become [UnknownMessage]. Invalid JSON throws
     * [ProtocolException]; a missing `t` or a known type with missing or mistyped required fields
     * throws [MalformedMessageException].
     */
    fun decode(text: String): Message {
        val obj: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("not a JSON object: ${e.message}")
        }
        val t = (obj["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw MalformedMessageException("missing string field t")
        val serializer = serializers[t] ?: return UnknownMessage(t)
        checkTypes(obj, serializer.descriptor, t)
        checkEnums(obj, t)
        return try {
            json.decodeFromJsonElement(Message.serializer(), obj)
        } catch (e: SerializationException) {
            throw MalformedMessageException("bad $t: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw MalformedMessageException("bad $t: ${e.message}")
        }
    }

    /**
     * PROTOCOL.md "Control channel": a value outside a listed set (an unknown `reason`, `action`,
     * `role` or `earcon`) is malformed, like a mistyped field — dropped, connection kept. Absent
     * fields are left to [checkTypes] and the serializer.
     */
    private fun checkEnums(obj: JsonObject, t: String) {
        for ((key, allowed) in ENUM_FIELDS) {
            if (key.first != t) continue
            val field = key.second
            val value = (obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (value !in allowed) {
                throw MalformedMessageException("$t.$field outside its set: $value")
            }
        }
    }

    /**
     * kotlinx.serialization reads `"7"` as a number and `7` as a string; the protocol does not.
     * Walks the JSON against the descriptor and rejects mistyped or null required values.
     */
    private fun checkTypes(e: JsonElement, d: SerialDescriptor, path: String) {
        fun bad(): Nothing = throw MalformedMessageException("mistyped $path")
        when (d.kind) {
            PrimitiveKind.STRING -> if (e !is JsonPrimitive || e is JsonNull || !e.isString) bad()
            PrimitiveKind.BOOLEAN -> if (e !is JsonPrimitive || e.isString || e.booleanOrNull == null) bad()
            is PrimitiveKind -> if (e !is JsonPrimitive || e is JsonNull || e.isString || e.doubleOrNull == null) bad()
            StructureKind.LIST -> {
                if (e !is JsonArray) bad()
                e.forEachIndexed { i, item -> checkTypes(item, d.getElementDescriptor(0), "$path[$i]") }
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (e !is JsonObject) bad()
                for (i in 0 until d.elementsCount) {
                    val value = e[d.getElementName(i)] ?: continue
                    val child = d.getElementDescriptor(i)
                    if (value is JsonNull) {
                        if (!child.isNullable) throw MalformedMessageException("null $path.${d.getElementName(i)}")
                        continue
                    }
                    checkTypes(value, child, "$path.${d.getElementName(i)}")
                }
            }
            else -> Unit
        }
    }

    fun frame(message: Message): ByteArray = frameText(encode(message))

    fun frameText(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        if (body.size > MAX_FRAME) throw ProtocolException("frame too large: ${body.size}")
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    /** Validates a 4-byte header and returns the body length; throws before any body is read. */
    fun bodyLength(header: ByteArray): Int {
        require(header.size >= 4)
        val len = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xffffffffL
        if (len > MAX_FRAME) throw ProtocolException("frame length $len exceeds $MAX_FRAME")
        return len.toInt()
    }
}

/** Blocking reader of framed JSON text. Returns null on a clean EOF between frames. */
class FrameReader(input: InputStream) {
    private val input = DataInputStream(input)
    private val header = ByteArray(4)

    fun readText(): String? {
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            return null
        }
        val len = Codec.bodyLength(header)
        val body = ByteArray(len)
        input.readFully(body)
        return String(body, Charsets.UTF_8)
    }

    fun read(): Message? = readText()?.let(Codec::decode)
}
