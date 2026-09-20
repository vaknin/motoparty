package com.kivan.motoparty.spike

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded PCM16 from a RIFF/WAVE file, already mixed down to mono. */
class Pcm(val sampleRate: Int, val samples: ShortArray) {
    val durationMs: Int get() = (samples.size * 1000L / sampleRate).toInt()
}

object Wav {

    /**
     * Minimal RIFF/WAVE reader: walks the chunk list, needs `fmt ` (PCM16 or float32) and `data`.
     * Android's TTS engines write plain PCM16, usually 22050 or 24000 Hz, mono — but nothing
     * guarantees that, so parse rather than assume.
     */
    fun parse(file: File): Pcm {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "wav too short: ${bytes.size} bytes" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(tag(bb, 0) == "RIFF" && tag(bb, 8) == "WAVE") {
            "not a RIFF/WAVE file (starts '${tag(bb, 0)}'/'${tag(bb, 8)}')"
        }

        var pos = 12
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0

        while (pos + 8 <= bytes.size) {
            val id = tag(bb, pos)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > bytes.size) {
                // Truncated final chunk: take whatever is actually there.
                if (id == "data") {
                    dataOff = body
                    dataLen = bytes.size - body
                }
                break
            }
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xFFFF
                    channels = bb.getShort(body + 2).toInt() and 0xFFFF
                    sampleRate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOff = body
                    dataLen = size
                }
            }
            pos = body + size + (size and 1) // chunks are word-aligned
        }

        require(dataOff >= 0) { "wav has no data chunk" }
        require(channels in 1..8 && sampleRate > 0) { "bad fmt: ch=$channels rate=$sampleRate" }

        val interleaved: ShortArray = when {
            bits == 16 -> ShortArray(dataLen / 2) { bb.getShort(dataOff + it * 2) }
            bits == 8 -> ShortArray(dataLen) { (((bytes[dataOff + it].toInt() and 0xFF) - 128) * 256).toShort() }
            bits == 32 && format == 3 -> ShortArray(dataLen / 4) {
                val f = bb.getFloat(dataOff + it * 4)
                (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            else -> throw IllegalArgumentException("unsupported wav: format=$format bits=$bits")
        }

        val mono = if (channels == 1) interleaved else ShortArray(interleaved.size / channels) { i ->
            var acc = 0
            for (c in 0 until channels) acc += interleaved[i * channels + c]
            (acc / channels).toShort()
        }
        return Pcm(sampleRate, mono)
    }

    private fun tag(bb: ByteBuffer, off: Int): String =
        String(ByteArray(4) { bb.get(off + it) }, Charsets.US_ASCII)

    /** Linear-interpolating resampler. Good enough: the recognizer is not a spectrum analyser. */
    fun resample(pcm: Pcm, targetRate: Int): Pcm {
        if (pcm.sampleRate == targetRate) return pcm
        val src = pcm.samples
        if (src.isEmpty()) return Pcm(targetRate, ShortArray(0))
        val ratio = pcm.sampleRate.toDouble() / targetRate
        val outLen = (src.size / ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val x = i * ratio
            val i0 = x.toInt()
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = x - i0
            out[i] = (src[i0] * (1 - frac) + src[i1] * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return Pcm(targetRate, out)
    }

    fun silence(rate: Int, ms: Int): ShortArray = ShortArray(rate * ms / 1000)

    /** Little-endian PCM16 bytes, which is exactly what the pipe wants. */
    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            out[j++] = (s.toInt() and 0xFF).toByte()
            out[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    /** Peak absolute amplitude, logged as a sanity check that the TTS actually produced sound. */
    fun peak(samples: ShortArray): Int {
        var m = 0
        for (s in samples) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > m) m = a
        }
        return m
    }
}
