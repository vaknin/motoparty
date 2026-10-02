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
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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
        MusicNext.serializer(), MusicStop.serializer(), MusicControl.serializer(), CommandText.serializer(),
        Announce.serializer(), State.serializer(), Bye.serializer(), MusicSearch.serializer(),
        MusicBrowse.serializer(), MusicResults.serializer(), MusicEnqueue.serializer(),
        MusicEdit.serializer(), MusicDownload.serializer(), MusicDownloads.serializer(),
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
        val message = try {
            json.decodeFromJsonElement(Message.serializer(), obj)
        } catch (e: SerializationException) {
            throw MalformedMessageException("bad $t: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw MalformedMessageException("bad $t: ${e.message}")
        }
        checkRules(message)
        return message
    }

    /**
     * The field rules a type alone cannot say: `music.edit move` requires a `to` ≥ 0, and
     * `music.control repeat` a `mode` (its value is checked by [checkEnums]), and
     * `music.download start` its `ids`.
     */
    private fun checkRules(m: Message) {
        if (m is MusicDownload && m.op == DownloadOp.START && m.ids == null) {
            throw MalformedMessageException("music.download start needs ids")
        }
        if (m is MusicControl && m.action == ControlAction.REPEAT && m.mode == null) {
            throw MalformedMessageException("music.control repeat needs a mode")
        }
        if (m is MusicEdit && m.op == EditOp.MOVE && (m.to == null || m.to < 0)) {
            throw MalformedMessageException("music.edit move needs a to >= 0, got ${m.to}")
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
            // A dotted field is nested ("music.repeat"): absent anywhere on the way = nothing to check.
            val path = field.split('.')
            val holder = path.dropLast(1).fold(obj as JsonObject?) { o, k -> o?.get(k) as? JsonObject } ?: continue
            val value = (holder[path.last()] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
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

    /** True when [message] frames within [MAX_FRAME]. */
    fun fits(message: Message): Boolean = encode(message).toByteArray(Charsets.UTF_8).size <= MAX_FRAME

    /**
     * PROTOCOL.md "Browsing" step 5: a `music.results` that would not fit loses its per-item
     * `art` first, then trailing items. An artist page (step 2a, 2026-10-02) loses its trailing
     * `albums` before any song: the top songs are what the page is played from.
     */
    fun fit(results: MusicResults): MusicResults {
        if (fits(results)) return results
        var r = results.copy(
            items = results.items.map { it.copy(art = null) },
            albums = results.albums?.map { it.copy(art = null) },
        )
        while (!fits(r) && !r.albums.isNullOrEmpty()) {
            val albums = r.albums!!
            r = r.copy(albums = albums.dropLast(maxOf(1, albums.size / 8)))
        }
        while (!fits(r) && r.items.isNotEmpty()) r = r.copy(items = r.items.dropLast(maxOf(1, r.items.size / 8)))
        return r
    }

    /** PROTOCOL.md "Browsing" step 6: a `music.downloads` that would not fit drops trailing `cached` ids. */
    fun fit(downloads: MusicDownloads): MusicDownloads {
        var d = downloads
        while (!fits(d) && d.cached.isNotEmpty()) d = d.copy(cached = d.cached.dropLast(maxOf(1, d.cached.size / 8)))
        return d
    }

    /**
     * A frame body as text. Bytes that are not valid UTF-8 are fatal like invalid JSON (the
     * connection closes), never replaced with U+FFFD and carried on with: PROTOCOL.md "Control
     * channel", and what the Swift codec does.
     */
    fun text(body: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (e: CharacterCodingException) {
        throw ProtocolException("frame is not valid UTF-8")
    }

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
        return Codec.text(body)
    }

    fun read(): Message? = readText()?.let(Codec::decode)
}
