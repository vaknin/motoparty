package com.kivan.motoparty.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class OpusDopsTest {
    /** What Media3 1.11.1 wrote for a YouTube itag 251 track: the OpusHead fields, little-endian. */
    private val media3Dops = bytes(0x00, 0x02, 0x38, 0x01, 0x80, 0xbb, 0x00, 0x00, 0x00, 0x00, 0x00)

    /** The WebM's csd-0: version 1, 2 channels, pre-skip 312, 48 kHz, gain 0, family 0. */
    private val opusHead = "OpusHead".toByteArray() + bytes(0x01, 0x02, 0x38, 0x01, 0x80, 0xbb, 0x00, 0x00, 0x00, 0x00, 0x00)

    @Test
    fun `pre-skip becomes 0 and the rest big-endian, nothing else changes`() {
        val file = mp4(media3Dops)
        val before = file.readBytes()
        patchOpusDops(file, opusHead)
        val after = file.readBytes()
        val at = dopsPayload(before)
        assertArrayEquals(bytes(0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0xbb, 0x80, 0x00, 0x00, 0x00), after.copyOfRange(at, at + 11))
        // The decoy "dOps" in mdat (audio data) and every byte outside the box are untouched.
        assertArrayEquals(before.copyOfRange(0, at), after.copyOfRange(0, at))
        assertArrayEquals(before.copyOfRange(at + 11, before.size), after.copyOfRange(at + 11, after.size))
    }

    @Test
    fun `a gain is carried over in the box's byte order`() {
        val head = opusHead.copyOf().also { it[16] = 0x00; it[17] = 0xff.toByte() } // -256 (Q7.8: -1 dB)
        val file = mp4(media3Dops)
        patchOpusDops(file, head)
        val out = file.readBytes()
        val at = dopsPayload(out)
        assertArrayEquals(bytes(0xff, 0x00), out.copyOfRange(at + 8, at + 10))
    }

    @Test
    fun `a file without dOps, or with another channel count, is refused`() {
        assertThrows(IOException::class.java) { patchOpusDops(mp4(null), opusHead) }
        val mono = media3Dops.copyOf().also { it[1] = 1 }
        assertThrows(IOException::class.java) { patchOpusDops(mp4(mono), opusHead) }
        assertThrows(IOException::class.java) { patchOpusDops(mp4(media3Dops), ByteArray(19)) }
    }

    // ---- a minimal MP4: ftyp, an mdat holding a decoy "dOps", then moov down to the sample entry ----

    private fun mp4(dops: ByteArray?): File {
        val opusEntry = box("Opus", ByteArray(28) + (dops?.let { box("dOps", it) } ?: ByteArray(0)))
        val stsd = box("stsd", bytes(0, 0, 0, 0, 0, 0, 0, 1) + opusEntry)
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))))
        val mdat = box("mdat", bytes(0, 0, 0, 19) + "dOps".toByteArray() + ByteArray(11) { 0x55 })
        return File.createTempFile("track", ".m4a").apply {
            deleteOnExit()
            writeBytes(box("ftyp", "M4A ".toByteArray() + ByteArray(4)) + mdat + moov)
        }
    }

    /** Offset of the real dOps payload: the last "dOps" (the decoy in mdat comes first). */
    private fun dopsPayload(b: ByteArray): Int {
        val s = String(b, Charsets.ISO_8859_1)
        return s.lastIndexOf("dOps") + 4
    }

    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val size = payload.size + 8
        write(bytes(size ushr 24, size ushr 16, size ushr 8, size))
        write(type.toByteArray(Charsets.US_ASCII))
        write(payload)
    }.toByteArray()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
