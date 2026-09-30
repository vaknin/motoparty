package com.kivan.motoparty.music

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * `opus-3s.webm` is 3 s of a 440 Hz sine, stereo 48 kHz Opus, made by ffmpeg (libopus, 48 kbps):
 * 151 packets of 22 498 bytes in all, 20 ms apart on WebM's 1 ms clock, pre-skip 312.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemuxTest {
    @Test
    fun `every packet is copied with its time, and dOps is patched`() {
        val mp4 = temp(".m4a")
        remuxWebmToMp4(fixture(), mp4)
        val track = readMp4(mp4.readBytes())

        assertEquals("audio/opus", track.format!!.sampleMimeType)
        assertEquals(2, track.format!!.channelCount)
        assertEquals(48_000, track.format!!.sampleRate)
        assertEquals(151, track.sizes.size)
        assertEquals(22_498, track.sizes.sum())
        assertEquals(0L, track.timesUs.first())
        track.timesUs.forEachIndexed { i, t -> assertTrue("packet $i at $t us", t in i * 20_000L..i * 20_000L + 1_000) }

        // Version 0, 2 channels, PreSkip 0, 48 000 Hz and gain 0 big-endian, mapping family 0.
        val bytes = mp4.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).lastIndexOf("dOps") + 4
        assertArrayEquals(
            byteArrayOf(0, 2, 0, 0, 0, 0, 0xbb.toByte(), 0x80.toByte(), 0, 0, 0),
            bytes.copyOfRange(at, at + 11),
        )
    }

    @Test
    fun `the packets are the WebM's own bytes`() {
        val webm = fixture().readBytes()
        val mp4 = temp(".m4a")
        remuxWebmToMp4(fixture(), mp4)
        val track = readMp4(mp4.readBytes())
        // Each packet sits whole in the WebM, in order (a SimpleBlock is a 4-byte header + packet).
        var from = 0
        for (packet in track.packets) {
            val at = indexOf(webm, packet, from)
            assertTrue("packet not found after $from", at >= 0)
            from = at + packet.size
        }
    }

    @Test
    fun `a file that is not WebM is refused`() {
        val junk = temp(".webm").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        assertThrows(IOException::class.java) { remuxWebmToMp4(junk, temp(".m4a")) }
    }

    private class Track : TrackOutput {
        var format: Format? = null
        val timesUs = ArrayList<Long>()
        val sizes = ArrayList<Int>()
        val packets = ArrayList<ByteArray>()
        private var pending = ByteArray(0)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val b = ByteArray(length)
            val n = input.read(b, 0, length)
            if (n > 0) pending += b.copyOf(n)
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pending += ByteArray(length).also { data.readBytes(it, 0, length) }
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            timesUs += timeUs
            sizes += size
            packets += pending.copyOfRange(pending.size - offset - size, pending.size - offset)
            pending = pending.copyOfRange(pending.size - offset, pending.size)
        }
    }

    /** The MP4's one track as stored: edit lists are ignored, so the times are the muxer's own. */
    private fun readMp4(bytes: ByteArray): Track {
        val track = Track()
        val extractor = Mp4Extractor(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = track
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val seek = PositionHolder()
        var position = 0L
        var result = Extractor.RESULT_SEEK
        while (result != Extractor.RESULT_END_OF_INPUT) {
            val stream = ByteArrayInputStream(bytes, position.toInt(), bytes.size - position.toInt())
            val input = DefaultExtractorInput({ b, off, len -> stream.read(b, off, len) }, position, bytes.size.toLong())
            do result = extractor.read(input, seek) while (result == Extractor.RESULT_CONTINUE)
            position = seek.position
        }
        assertEquals(C.TRACK_TYPE_AUDIO, androidx.media3.common.MimeTypes.getTrackType(track.format!!.sampleMimeType))
        return track
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun fixture() = temp(".webm").apply {
        writeBytes(RemuxTest::class.java.getResourceAsStream("/opus-3s.webm")!!.readBytes())
    }

    private fun temp(suffix: String): File = File.createTempFile("remux", suffix).apply { deleteOnExit() }
}
