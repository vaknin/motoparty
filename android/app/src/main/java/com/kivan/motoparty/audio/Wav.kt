package com.kivan.motoparty.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 44-byte canonical WAV header, and nothing else: pure, no Android, no I/O. [PcmDump] writes
 * one of these with a zero length when it opens the file and rewrites it with the real length when
 * it closes, so a dump cut short by a crash is still a playable file with a wrong duration.
 *
 * Stage A of the wired-mic plan (2026-09-20): nothing in this app could write PCM to a file, and
 * every A/B of a microphone against another one is measured on recordings.
 */
object Wav {
    /** RIFF + fmt + data, uncompressed PCM: always exactly this long. */
    const val HEADER_BYTES = 44

    /**
     * [dataBytes] is what follows this header. Both size fields are unsigned 32-bit, so a dump
     * longer than 4 GiB cannot be described; the length is clamped rather than wrapped, which keeps
     * the file readable up to the point the header claims.
     */
    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataBytes: Long): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val data = dataBytes.coerceIn(0L, MAX_UINT32 - (HEADER_BYTES - 8))
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((data + HEADER_BYTES - 8).toInt()) // everything after this field
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16) // PCM fmt chunk length
        b.putShort(1) // format 1 = uncompressed PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign) // byte rate
        b.putShort(blockAlign.toShort())
        b.putShort(bitsPerSample.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(data.toInt())
        return b.array()
    }

    private const val MAX_UINT32 = 0xffff_ffffL
}
