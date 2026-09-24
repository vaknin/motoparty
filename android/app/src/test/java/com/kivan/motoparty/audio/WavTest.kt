package com.kivan.motoparty.audio

import com.kivan.motoparty.core.VoicePacket
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Wav] is 44 bytes of arithmetic that decides whether a recording opens at all. The case that
 * matters is the one [PcmDump] writes: 16 kHz mono 16-bit, the rate PROTOCOL.md pins the wire to
 * and the rate the capture loop reads at.
 */
class WavTest {

    private fun header(dataBytes: Long) = Wav.header(VoicePacket.SAMPLE_RATE, 1, 16, dataBytes)

    private fun int(b: ByteArray, at: Int): Int =
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun short(b: ByteArray, at: Int): Int =
        ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    private fun text(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    @Test
    fun `the chunks are where a player looks for them`() {
        val h = header(640)
        assertEquals(Wav.HEADER_BYTES, h.size)
        assertEquals("RIFF", text(h, 0))
        assertEquals("WAVE", text(h, 8))
        assertEquals("fmt ", text(h, 12))
        assertEquals(16, int(h, 16))
        assertEquals("uncompressed PCM", 1, short(h, 20))
        assertEquals("data", text(h, 36))
    }

    @Test
    fun `one talk frame of 16 kHz mono`() {
        val h = header(640) // one 20 ms frame: 320 samples, 2 bytes each
        assertEquals(1, short(h, 22)) // channels
        assertEquals(16_000, int(h, 24)) // sample rate
        assertEquals("16 kHz x 2 bytes", 32_000, int(h, 28)) // byte rate
        assertEquals(2, short(h, 32)) // block align
        assertEquals(16, short(h, 34)) // bits per sample
        assertEquals(640, int(h, 40))
        assertEquals("everything after the size field", 640 + 36, int(h, 4))
    }

    @Test
    fun `the header written when the file is opened claims nothing`() {
        val h = header(0)
        assertEquals(0, int(h, 40))
        assertEquals(36, int(h, 4))
    }

    @Test
    fun `a length beyond the format is clamped, not wrapped`() {
        // Both size fields are unsigned 32-bit. A wrapped length makes an unopenable file; a
        // clamped one plays up to what the header claims. Neither can happen under PcmDump's own
        // cap — this is about the class being honest, not about the dump.
        val h = header(5L * 1024 * 1024 * 1024)
        assertEquals("0xffffffff minus the header", (0xffff_ffffL - 36).toInt(), int(h, 40))
        assertEquals("0xffffffff", -1, int(h, 4))
    }
}
