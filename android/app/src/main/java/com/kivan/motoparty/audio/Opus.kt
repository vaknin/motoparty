package com.kivan.motoparty.audio

/** JNI bindings to the vendored libopus (src/main/cpp/opus_jni.c). */
object Opus {
    init {
        System.loadLibrary("motoparty_opus")
    }

    @JvmStatic external fun encoderCreate(rate: Int, channels: Int, bitrate: Int, lossPercent: Int): Long
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray): Int
    @JvmStatic external fun encoderInDtx(handle: Long): Int
    @JvmStatic external fun encoderDestroy(handle: Long)
    @JvmStatic external fun decoderCreate(rate: Int, channels: Int): Long
    @JvmStatic external fun decode(handle: Long, data: ByteArray?, len: Int, pcm: ShortArray, frameSize: Int, fec: Int): Int
    @JvmStatic external fun decoderDestroy(handle: Long)
    @JvmStatic external fun version(): String
}

/** Encoder configured per PROTOCOL.md: VOIP, 16 kHz mono, 24 kbps, FEC at 10 % loss, DTX. */
class OpusEncoder : AutoCloseable {
    private var handle = Opus.encoderCreate(RATE, 1, BITRATE, LOSS_PERCENT).also {
        check(it != 0L) { "opus_encoder_create failed" }
    }
    /** The last encoded packet: the first bytes of it, as many as [encode] returned. Reused. */
    val packet = ByteArray(MAX_PACKET)

    /** Encodes into [packet] and returns its length (1-2 bytes means "nothing to send" under DTX). */
    fun encode(pcm: ShortArray): Int {
        val n = Opus.encode(handle, pcm, pcm.size, packet)
        check(n >= 0) { "opus_encode failed: $n" }
        return n
    }

    val inDtx: Boolean get() = Opus.encoderInDtx(handle) != 0

    override fun close() {
        Opus.encoderDestroy(handle)
        handle = 0
    }

    companion object {
        const val RATE = 16_000
        const val BITRATE = 24_000
        const val LOSS_PERCENT = 10
        /** The largest Opus packet there is. */
        const val MAX_PACKET = 1275
    }
}

class OpusDecoder : AutoCloseable {
    private var handle = Opus.decoderCreate(OpusEncoder.RATE, 1).also {
        check(it != 0L) { "opus_decoder_create failed" }
    }

    fun decode(packet: ByteArray, pcm: ShortArray): Int = Opus.decode(handle, packet, packet.size, pcm, pcm.size, 0)

    /** Recovers the frame *before* [successor] from the in-band FEC data it carries. */
    fun decodeFec(successor: ByteArray, pcm: ShortArray): Int =
        Opus.decode(handle, successor, successor.size, pcm, pcm.size, 1)

    fun conceal(pcm: ShortArray): Int = Opus.decode(handle, null, 0, pcm, pcm.size, 0)

    override fun close() {
        Opus.decoderDestroy(handle)
        handle = 0
    }
}
