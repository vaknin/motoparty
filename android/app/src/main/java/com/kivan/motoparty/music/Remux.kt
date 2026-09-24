package com.kivan.motoparty.music

import android.media.MediaCodec
import android.media.MediaExtractor
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.MediaMuxerCompat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Copies the Opus audio of a WebM file into an MP4 file, packet for packet: nothing is decoded or
 * re-encoded. AVPlayer has no WebM demuxer but plays Opus in MP4 (iOS 17+), and ExoPlayer plays
 * either. The platform [MediaExtractor] demuxes WebM and hands over the OpusHead as csd-0..2;
 * Media3's muxer writes it as the `dOps` box, which [patchOpusDops] then puts right.
 */
@OptIn(UnstableApi::class)
fun remuxWebmToMp4(src: File, dst: File) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(src.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == "audio/opus"
        } ?: throw IOException("no Opus track in ${src.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val opusHead = format.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
            ?: throw IOException("no OpusHead in ${src.name}")
        val muxer = MediaMuxerCompat(dst.path, MediaMuxerCompat.OUTPUT_FORMAT_MP4)
        try {
            val out = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(MAX_PACKET)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                // The muxer wants remaining() == size; with batching off (the default) it writes
                // the packet before returning, so the one buffer is reused.
                buffer.position(0).limit(size)
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(out, buffer, info)
                samples++
                extractor.advance()
            }
            if (samples == 0) throw IOException("no Opus packets in ${src.name}")
            muxer.stop()
        } finally {
            muxer.release()
        }
        patchOpusDops(dst, opusHead)
    } finally {
        extractor.release()
    }
}

/** An Opus packet is at most 120 ms of 510 kbps audio (~7.7 KB); leave room. */
private const val MAX_PACKET = 64 * 1024
