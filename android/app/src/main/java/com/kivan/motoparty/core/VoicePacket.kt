package com.kivan.motoparty.core

import java.nio.ByteBuffer

/** UDP voice packet, PROTOCOL.md "Voice": magic, kind, seq u16, ts u32 (big-endian), payload. */
class VoicePacket(val kind: Int, val seq: Int, val ts: Long, val payload: ByteArray) {
    init {
        require(seq in 0..0xffff) { "seq out of range" }
        require(ts in 0..0xffffffffL) { "ts out of range" }
    }

    fun encode(): ByteArray =
        ByteBuffer.allocate(HEADER + payload.size)
            .put(MAGIC).put(kind.toByte()).putShort(seq.toShort()).putInt(ts.toInt()).put(payload)
            .array()

    companion object {
        const val HEADER = 8
        const val MAGIC: Byte = 0x4D
        const val KIND_AUDIO = 1
        const val KIND_KEEPALIVE = 2
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
        const val FRAME_MS = 20

        fun decode(data: ByteArray, length: Int = data.size): VoicePacket? {
            if (length < HEADER || data[0] != MAGIC) return null
            val kind = data[1].toInt() and 0xff
            if (kind != KIND_AUDIO && kind != KIND_KEEPALIVE) return null
            val buf = ByteBuffer.wrap(data, 0, length)
            val seq = buf.getShort(2).toInt() and 0xffff
            val ts = buf.getInt(4).toLong() and 0xffffffffL
            return VoicePacket(kind, seq, ts, data.copyOfRange(HEADER, length))
        }
    }
}
