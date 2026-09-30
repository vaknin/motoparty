package com.kivan.motoparty.core

/** Where the capture loops hand an encoded frame: the first [length] bytes of [payload], which is reused. */
fun interface VoiceSend {
    fun send(ts: Long, payload: ByteArray, length: Int)
}

/** UDP voice packet, PROTOCOL.md "Voice": magic, kind, seq u16, ts u32 (big-endian), payload. */
class VoicePacket(val kind: Int, val seq: Int, val ts: Long, val payload: ByteArray) {
    init {
        require(seq in 0..0xffff) { "seq out of range" }
        require(ts in 0..0xffffffffL) { "ts out of range" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(HEADER + payload.size)
        writeHeader(out, kind, seq, ts)
        payload.copyInto(out, HEADER)
        return out
    }

    companion object {
        const val HEADER = 8
        const val MAGIC: Byte = 0x4D
        const val KIND_AUDIO = 1
        const val KIND_KEEPALIVE = 2
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
        const val FRAME_MS = 20

        /** Writes the 8 header bytes at the start of [out]; the payload goes at [HEADER]. */
        fun writeHeader(out: ByteArray, kind: Int, seq: Int, ts: Long) {
            out[0] = MAGIC
            out[1] = kind.toByte()
            out[2] = (seq shr 8).toByte()
            out[3] = seq.toByte()
            out[4] = (ts shr 24).toByte()
            out[5] = (ts shr 16).toByte()
            out[6] = (ts shr 8).toByte()
            out[7] = ts.toByte()
        }

        fun decode(data: ByteArray, length: Int = data.size): VoicePacket? {
            if (length < HEADER || data[0] != MAGIC) return null
            val kind = data[1].toInt() and 0xff
            if (kind != KIND_AUDIO && kind != KIND_KEEPALIVE) return null
            val seq = (data[2].toInt() and 0xff shl 8) or (data[3].toInt() and 0xff)
            val ts = (data[4].toLong() and 0xff shl 24) or (data[5].toLong() and 0xff shl 16) or
                (data[6].toLong() and 0xff shl 8) or (data[7].toLong() and 0xff)
            return VoicePacket(kind, seq, ts, data.copyOfRange(HEADER, length))
        }
    }
}
