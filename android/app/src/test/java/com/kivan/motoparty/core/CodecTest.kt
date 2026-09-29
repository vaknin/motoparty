package com.kivan.motoparty.core

import com.kivan.motoparty.core.Fixtures.hex
import com.kivan.motoparty.core.Fixtures.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class CodecTest {
    @Test
    fun everyMessageRoundTrips() {
        val messages = Fixtures.load("control/messages.json").jsonObject["messages"]!!.jsonArray
        assertTrue(messages.size >= 20)
        for (m in messages) {
            val decoded = Codec.decode(m.toString())
            assertTrue("decoded as unknown: $m", decoded !is UnknownMessage)
            val reencoded = Codec.encode(decoded)
            assertEquals("round trip of $m", m, Json.parseToJsonElement(reencoded))
            assertEquals(decoded, Codec.decode(reencoded))
        }
    }

    @Test
    fun validFramesDecodeAndEncodeByteExact() {
        val framing = Fixtures.load("control/framing.json").jsonObject
        for (v in framing["valid"]!!.jsonArray) {
            val json = v.jsonObject["json"]!!.jsonPrimitive.content
            val bytes = hex(v.jsonObject["hex"]!!.jsonPrimitive.content)
            val text = FrameReader(ByteArrayInputStream(bytes)).readText()!!
            assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(text))
            // Our encoder is compact, so the fixture's compact JSON frames byte-for-byte.
            assertEquals(bytes.toHex(), Codec.frame(Codec.decode(json)).toHex())
        }
    }

    @Test
    fun oversizeHeaderRejectedBeforeBody() {
        val framing = Fixtures.load("control/framing.json").jsonObject
        for (v in framing["invalid"]!!.jsonArray) {
            val header = hex(v.jsonObject["hex"]!!.jsonPrimitive.content)
            try {
                // Only the header is supplied: a reader that tried to read the body would EOF.
                FrameReader(ByteArrayInputStream(header)).readText()
                fail("accepted ${v.jsonObject["why"]}")
            } catch (_: ProtocolException) {
            }
        }
    }

    @Test
    fun exactly64KiBIsAccepted() {
        assertEquals(65536, Codec.bodyLength(hex("00010000")))
    }

    @Test
    fun unknownTypesAndFieldsAreTolerated() {
        val unknown = Fixtures.load("control/framing.json").jsonObject["unknown"]!!.jsonArray
        assertEquals(UnknownMessage("future.thing"), Codec.decode(unknown[0].jsonObject["json"]!!.jsonPrimitive.content))
        assertEquals(Ping(1, 2), Codec.decode(unknown[1].jsonObject["json"]!!.jsonPrimitive.content))
    }

    @Test
    fun malformedVectorsAreDroppedNotFatal() {
        val malformed = Fixtures.load("control/framing.json").jsonObject["malformed"]!!.jsonArray
        assertTrue(malformed.isNotEmpty())
        for (v in malformed) {
            val json = v.jsonObject["json"]!!.jsonPrimitive.content
            try {
                Codec.decode(json)
                fail("accepted malformed $json")
            } catch (_: MalformedMessageException) {
            }
        }
    }

    /**
     * PROTOCOL.md "Control channel": a value outside a listed set is malformed, not fatal and not
     * silently accepted. Volume actions were removed from `music.control` (volume is local), so
     * they are now just another out-of-set value.
     */
    @Test
    fun valuesOutsideAnEnumAreMalformed() {
        val bad = listOf(
            """{"t":"music.control","action":"volumeUp"}""",
            """{"t":"music.control","action":"volumeDown"}""",
            """{"t":"music.control","action":"rewind"}""",
            """{"t":"talk.close","by":"host","reason":"bored"}""",
            """{"t":"talk.open","by":"passenger"}""",
            """{"t":"hello","proto":1,"role":"middle","name":"x"}""",
            """{"t":"announce","text":"hi","earcon":"fanfare"}""",
        )
        for (json in bad) {
            try {
                Codec.decode(json)
                fail("accepted out-of-set value: $json")
            } catch (_: MalformedMessageException) {
            }
        }
    }

    @Test
    fun valuesInsideAnEnumAreAccepted() {
        assertEquals(TalkClose("host", "unavailable"), Codec.decode("""{"t":"talk.close","by":"host","reason":"unavailable"}"""))
        assertEquals(TalkClose("client", "unavailable"), Codec.decode("""{"t":"talk.close","by":"client","reason":"unavailable"}"""))
        for (action in listOf("pause", "resume", "next", "previous")) {
            assertEquals(MusicControl(action), Codec.decode("""{"t":"music.control","action":"$action"}"""))
        }
        // An absent optional enum field is not a value outside the set.
        assertEquals(Announce("hi", null), Codec.decode("""{"t":"announce","text":"hi"}"""))
    }

    @Test
    fun fatalVectorsCloseTheConnection() {
        val fatal = Fixtures.load("control/framing.json").jsonObject["fatal"]!!.jsonArray
        assertTrue(fatal.isNotEmpty())
        for (v in fatal) {
            val bytes = hex(v.jsonObject["hex"]!!.jsonPrimitive.content)
            try {
                FrameReader(ByteArrayInputStream(bytes)).read()
                fail("accepted fatal frame ${v.jsonObject["_doc"]}")
            } catch (e: ProtocolException) {
                assertTrue("${v.jsonObject["_doc"]} must be fatal, not droppable", e !is MalformedMessageException)
            }
        }
    }

    @Test
    fun cleanEofReturnsNull() {
        assertNull(FrameReader(ByteArrayInputStream(ByteArray(0))).readText())
    }

    @Test
    fun optionalFieldsAreOmittedNotNull() {
        assertEquals("""{"t":"bye"}""", Codec.encode(Bye()))
        assertEquals("""{"t":"hello","proto":1,"role":"client","name":"x"}""", Codec.encode(Hello(proto = 1, role = "client", name = "x")))
        assertEquals("""{"t":"state","talk":false,"queue":[]}""", Codec.encode(State(talk = false, queue = emptyList())))
        assertEquals("""{"t":"music.stop"}""", Codec.encode(MusicStop))
    }

    @Test(expected = MalformedMessageException::class)
    fun knownTypeWithMissingFieldIsMalformed() {
        Codec.decode("""{"t":"ping","id":1}""")
    }

    @Test(expected = MalformedMessageException::class)
    fun mistypedFieldIsMalformed() {
        Codec.decode("""{"t":"music.ready","id":5}""")
    }

    @Test
    fun invalidJsonIsFatal() {
        try {
            Codec.decode("{not json")
            fail()
        } catch (e: ProtocolException) {
            assertTrue(e !is MalformedMessageException)
        }
    }

    @Test
    fun oversizeResultsLoseArtThenTrailingItems() {
        val art = "https://lh3.googleusercontent.com/" + "a".repeat(200)
        val small = MusicResults(1, List(10) { ResultItem("id$it", "t", "a", art = art) })
        assertEquals(small, Codec.fit(small))

        val big = MusicResults(2, List(300) { ResultItem("id$it", "title $it", "artist", durationMs = 1000, art = art) })
        val fitted = Codec.fit(big)
        assertTrue(Codec.fits(fitted))
        assertEquals(300, fitted.items.size) // dropping the art was enough
        assertTrue(fitted.items.all { it.art == null })

        val huge = MusicResults(3, List(2000) { ResultItem("id$it", "ש".repeat(20), "artist", durationMs = 1000) })
        val cut = Codec.fit(huge)
        assertTrue(Codec.fits(cut))
        assertEquals("id0", cut.items.first().ref)
        assertTrue(cut.items.size in 100 until 2000)
    }
}
