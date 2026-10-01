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

    /** PROTOCOL.md "Repeat by touch": `repeat` carries the mode to set, `off` included, and needs it. */
    @Test
    fun repeatControlCarriesItsMode() {
        for (mode in RepeatMode.entries) {
            val m = MusicControl(ControlAction.REPEAT, mode.word)
            assertEquals("""{"t":"music.control","action":"repeat","mode":"${mode.word}"}""", Codec.encode(m))
            assertEquals(m, Codec.decode(Codec.encode(m)))
        }
        // The other actions never send a mode.
        assertEquals("""{"t":"music.control","action":"next"}""", Codec.encode(MusicControl(ControlAction.NEXT)))
        // A valid mode on another action is ignored by the host (kept by the codec).
        assertEquals(MusicControl("pause", "track"), Codec.decode("""{"t":"music.control","action":"pause","mode":"track"}"""))
        for (json in listOf(
            """{"t":"music.control","action":"repeat"}""",
            """{"t":"music.control","action":"repeat","mode":"all"}""",
            """{"t":"music.control","action":"repeat","mode":"Track"}""",
            """{"t":"music.control","action":"repeat","mode":null}""",
            """{"t":"music.control","action":"repeat","mode":1}""",
            """{"t":"music.control","action":"pause","mode":"all"}""",
        )) {
            try {
                Codec.decode(json)
                fail("accepted $json")
            } catch (_: MalformedMessageException) {
            }
        }
    }

    /** PROTOCOL.md "Browsing" step 6: `start` needs `ids`, `stop` does not; `op` is a closed set. */
    @Test
    fun downloadStartNeedsIds() {
        val start = MusicDownload(DownloadOp.START, "PL1", listOf("a1", "a2"))
        assertEquals("""{"t":"music.download","op":"start","ref":"PL1","ids":["a1","a2"]}""", Codec.encode(start))
        assertEquals(start, Codec.decode(Codec.encode(start)))
        assertEquals("""{"t":"music.download","op":"stop","ref":"PL1"}""", Codec.encode(MusicDownload(DownloadOp.STOP, "PL1")))
        assertEquals(MusicDownload(DownloadOp.START, "PL1", emptyList()), Codec.decode("""{"t":"music.download","op":"start","ref":"PL1","ids":[]}"""))
        for (json in listOf(
            """{"t":"music.download","op":"start","ref":"PL1"}""",
            """{"t":"music.download","op":"start","ref":"PL1","ids":null}""",
            """{"t":"music.download","op":"start","ref":"PL1","ids":"a1"}""",
            """{"t":"music.download","op":"start","ref":"PL1","ids":[1]}""",
            """{"t":"music.download","op":"Stop","ref":"PL1"}""",
            """{"t":"music.download","op":"stop"}""",
            """{"t":"music.downloads","cached":[]}""",
            """{"t":"music.downloads","cached":[],"downloads":[{"ref":"PL1","done":0,"total":1,"failed":0}]}""",
            """{"t":"state","talk":false,"queue":[],"busy":1}""",
        )) {
            try {
                Codec.decode(json)
                fail("accepted $json")
            } catch (_: MalformedMessageException) {
            }
        }
    }

    /** `state.busy` is optional and omitted when absent; `music.downloads` keeps its frame by dropping cached ids. */
    @Test
    fun busyAndDownloadsFit() {
        assertEquals("""{"t":"state","talk":false,"queue":[],"busy":"Searching song \"x\""}""",
            Codec.encode(State(talk = false, queue = emptyList(), busy = "Searching song \"x\"")))
        val progress = listOf(DownloadItem("PL1", 1, 3, 0, true))
        val small = MusicDownloads(listOf("a1"), progress)
        assertEquals(small, Codec.fit(small))
        val big = MusicDownloads((0 until 6000).map { "track-%05d".format(it) }, progress)
        val fitted = Codec.fit(big)
        assertTrue(Codec.fits(fitted))
        assertTrue(fitted.cached.size in 1 until big.cached.size)
        assertEquals(big.cached.take(fitted.cached.size), fitted.cached)
        assertEquals(progress, fitted.downloads)
    }

    /** PROTOCOL.md "Host-mic talk": `mic` is optional on `talk.open` and `state`, and only ever "host". */
    @Test
    fun hostMicIsOptionalAndOnlyHost() {
        assertEquals("""{"t":"talk.open","by":"host"}""", Codec.encode(TalkOpen(Role.HOST)))
        assertEquals("""{"t":"talk.open","by":"client","mic":"host"}""", Codec.encode(TalkOpen(Role.CLIENT, Mic.HOST)))
        assertEquals(TalkOpen(Role.HOST, Mic.HOST), Codec.decode("""{"t":"talk.open","by":"host","mic":"host"}"""))
        assertEquals(TalkOpen(Role.CLIENT), Codec.decode("""{"t":"talk.open","by":"client"}"""))
        assertEquals("""{"t":"state","talk":false,"queue":[]}""", Codec.encode(State(talk = false, queue = emptyList())))
        assertEquals(
            """{"t":"state","talk":true,"queue":[],"mic":"host"}""",
            Codec.encode(State(talk = true, queue = emptyList(), mic = Mic.HOST)),
        )
        for (json in listOf(
            """{"t":"talk.open","by":"host","mic":"client"}""",
            """{"t":"talk.open","by":"host","mic":1}""",
            """{"t":"state","talk":true,"queue":[],"mic":"earbuds"}""",
        )) {
            try {
                Codec.decode(json)
                fail("accepted $json")
            } catch (_: MalformedMessageException) {
            }
        }
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

    @Test
    fun musicNextAndQueueItemOptionals() {
        assertEquals("""{"t":"music.next","id":"a","atHostTimeMs":5}""", Codec.encode(MusicNext("a", 5)))
        assertEquals(MusicNext("a", 5), Codec.decode("""{"t":"music.next","id":"a","atHostTimeMs":5}"""))
        val state = State(
            talk = false,
            queue = listOf(
                QueueItem("a", "T", "A", durationMs = 1000, art = "https://x/a.jpg"),
                QueueItem("b", "T", "A", durationMs = 2000),
                QueueItem("c", "T", "A"),
            ),
        )
        assertEquals(
            """{"t":"state","talk":false,"queue":[""" +
                """{"id":"a","title":"T","artist":"A","durationMs":1000,"art":"https://x/a.jpg"},""" +
                """{"id":"b","title":"T","artist":"A","durationMs":2000},""" +
                """{"id":"c","title":"T","artist":"A"}]}""",
            Codec.encode(state),
        )
        assertEquals(state, Codec.decode(Codec.encode(state)))
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

    /** P11: invalid UTF-8 is fatal (not replaced with U+FFFD and processed), like invalid JSON. */
    @Test
    fun invalidUtf8IsFatal() {
        val bodies = listOf(
            "7b2274223a22627965222c22726561736f6e223a22ff227d", // a lone 0xFF inside a string
            "7b2274223a22627965222c22726561736f6e223a22c328227d", // a cut two-byte sequence
            "7b2274223a22627965222c22726561736f6e223a22eda080227d", // an encoded surrogate
            "7b2274223a22627965222c22726561736f6e223a22c0af227d", // an overlong form
        )
        for (h in bodies) {
            val body = hex(h)
            val frame = java.nio.ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
            try {
                FrameReader(ByteArrayInputStream(frame)).read()
                fail("accepted $h")
            } catch (e: ProtocolException) {
                assertTrue("$h must be fatal, not droppable", e !is MalformedMessageException)
            }
        }
        // Valid multi-byte text still passes.
        assertEquals("""{"t":"bye","reason":"שלום 🏍"}""", Codec.text("""{"t":"bye","reason":"שלום 🏍"}""".toByteArray()))
    }
}
