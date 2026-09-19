package com.kivan.motoparty.core

import com.kivan.motoparty.core.Fixtures.hex
import com.kivan.motoparty.core.Fixtures.toHex
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePacketTest {
    private val f = Fixtures.load("voice/header.json").jsonObject

    @Test
    fun validVectorsEncodeAndDecode() {
        for (v in f["valid"]!!.jsonArray) {
            val o = v.jsonObject
            val kind = o["kind"]!!.jsonPrimitive.int
            val seq = o["seq"]!!.jsonPrimitive.int
            val ts = o["ts"]!!.jsonPrimitive.long
            val payload = hex(o["payloadHex"]!!.jsonPrimitive.content)
            val wire = o["hex"]!!.jsonPrimitive.content
            assertEquals(wire, VoicePacket(kind, seq, ts, payload).encode().toHex())
            val p = VoicePacket.decode(hex(wire)) ?: error("rejected $wire")
            assertEquals(kind, p.kind)
            assertEquals(seq, p.seq)
            assertEquals(ts, p.ts)
            assertEquals(payload.toHex(), p.payload.toHex())
        }
    }

    @Test
    fun invalidVectorsRejected() {
        for (v in f["invalid"]!!.jsonArray) {
            assertNull(v.toString(), VoicePacket.decode(hex(v.jsonObject["hex"]!!.jsonPrimitive.content)))
        }
    }

    @Test
    fun decodeHonoursLength() {
        val buf = hex("4d01000100000140fc01") + ByteArray(20)
        assertEquals("fc01", VoicePacket.decode(buf, 10)!!.payload.toHex())
    }
}
