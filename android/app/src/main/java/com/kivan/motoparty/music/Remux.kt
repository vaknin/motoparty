package com.kivan.motoparty.music

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.MuxerException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Copies the Opus audio of a WebM file into an MP4 file, packet for packet: nothing is decoded or
 * re-encoded. AVPlayer has no WebM demuxer but plays Opus in MP4 (iOS 17+), and ExoPlayer plays
 * either. Media3's [MatroskaExtractor] demuxes the WebM and hands over the OpusHead, codec delay and
 * seek pre-roll as csd-0..2; Media3's muxer writes the OpusHead as the `dOps` box, which
 * [patchOpusDops] then puts right.
 *
 * The platform `MediaExtractor` is not used: its `advance()` costs ~0.4 ms per packet on a Pixel,
 * 5-10 s for one song (measured 2026-09-30), and that was the whole wait between a tap and sound.
 */
@OptIn(UnstableApi::class)
fun remuxWebmToMp4(src: File, dst: File) {
    // Batching writes the packets in chunks of about a second instead of one by one (15 000 file
    // writes for a song); the muxer then keeps each packet until its chunk is written, so it copies.
    val muxer = Mp4Muxer.Builder(FileOutputStream(dst))
        .setSampleBatchingEnabled(true)
        .setSampleCopyingEnabled(true)
        .build()
    val opus = OpusToMuxer(muxer)
    var closed = false
    try {
        val extractor = MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        extractor.init(object : ExtractorOutput {
            var taken = false

            override fun track(id: Int, type: Int): TrackOutput =
                if (type == C.TRACK_TYPE_AUDIO && !taken) opus.also { taken = true } else DiscardingTrackOutput()

            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        FileInputStream(src).use { file ->
            val length = src.length()
            val seek = PositionHolder()
            var position = 0L
            var result = Extractor.RESULT_SEEK
            while (result != Extractor.RESULT_END_OF_INPUT) {
                file.channel.position(position)
                val stream = BufferedInputStream(file, READ_BUFFER)
                val input = DefaultExtractorInput(DataReader { b, off, len -> stream.read(b, off, len) }, position, length)
                try {
                    do result = extractor.read(input, seek) while (result == Extractor.RESULT_CONTINUE)
                } catch (e: RuntimeException) {
                    // The EBML reader checks a damaged file with IllegalStateException.
                    throw IOException("${src.name} is not readable WebM", e)
                }
                position = seek.position
            }
        }
        extractor.release()
        if (opus.opusHead == null) throw IOException("no Opus track in ${src.name}")
        if (opus.samples == 0) throw IOException("no Opus packets in ${src.name}")
        closed = true
        muxer.close()
    } catch (e: MuxerException) {
        throw IOException("writing ${dst.name} failed", e)
    } finally {
        if (!closed) try { muxer.close() } catch (_: MuxerException) {}
    }
    patchOpusDops(dst, opus.opusHead!!)
}

/** The extractor's one audio track: every packet goes to [muxer] as it completes. */
@OptIn(UnstableApi::class)
private class OpusToMuxer(private val muxer: Mp4Muxer) : TrackOutput {
    var opusHead: ByteArray? = null
        private set
    var samples = 0
        private set

    private var track = -1
    private var pending = ByteArray(MAX_PACKET)
    private var filled = 0
    /** Supplemental bytes of the current sample, counted in its size but not kept. */
    private var extra = 0

    override fun format(format: Format) {
        if (track >= 0) return
        if (format.sampleMimeType != MimeTypes.AUDIO_OPUS) throw IOException("audio is ${format.sampleMimeType}, not Opus")
        opusHead = format.initializationData.firstOrNull() ?: throw IOException("no OpusHead")
        track = muxer.addTrack(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        room(length)
        val read = input.read(pending, filled, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw java.io.EOFException()
        }
        filled += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            // The discard padding of the last packet, appended for Media3's own decoder: not audio.
            data.skipBytes(length)
            extra += length
            return
        }
        room(length)
        data.readBytes(pending, filled, length)
        filled += length
    }

    /** The sample is the size bytes (less the [extra] ones) that end [offset] bytes before the end of what was handed over. */
    override fun sampleMetadata(timeUs: Long, flags: Int, sizeWithExtra: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (track < 0) throw IOException("Opus packet before the track header")
        val size = sizeWithExtra - extra
        extra = 0
        val start = filled - offset - size
        if (size <= 0 || start < 0) throw IOException("Opus packet of $size bytes, $filled read")
        // The muxer copies the packet, so the one array is reused.
        muxer.writeSampleData(track, ByteBuffer.wrap(pending, start, size), BufferInfo(timeUs, size, C.BUFFER_FLAG_KEY_FRAME))
        samples++
        System.arraycopy(pending, filled - offset, pending, 0, offset)
        filled = offset
    }

    private fun room(length: Int) {
        if (filled + length > pending.size) pending = pending.copyOf(maxOf(filled + length, pending.size * 2))
    }
}

/** An Opus packet is at most 120 ms of 510 kbps audio (~7.7 KB); leave room. */
private const val MAX_PACKET = 64 * 1024
private const val READ_BUFFER = 256 * 1024
