package com.kivan.motoparty.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [PcmDump] is the Stage A instrument: the capture loop's own PCM on disk, so two microphones can
 * be compared by ear instead of by argument. Everything here is about the one rule it lives under —
 * **the `voice-capture` thread never blocks on I/O** — so the tests drive the writer by hand where
 * they have to, which is the only way to see the overflow branch without a race.
 */
class PcmDumpTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rate = 16_000
    private val frame = 320

    /** A frame whose every sample is [v], the way the capture loop hands one over. */
    private fun frame(v: Int) = ShortArray(frame) { v.toShort() }

    private fun samples(file: File): ShortArray {
        val bytes = file.readBytes()
        val data = bytes.copyOfRange(Wav.HEADER_BYTES, bytes.size)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(data.size / 2) { b.short }
    }

    private fun dataLength(file: File): Int =
        ByteBuffer.wrap(file.readBytes(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun `the frames offered are the samples on disk, and the header says how many`() {
        val file = File(tmp.newFolder(), "captures/capture-1.wav") // the dir does not exist yet
        val dump = PcmDump(file, rate, frame)
        dump.start()
        dump.offer(frame(1))
        dump.offer(frame(-32_768))
        dump.offer(frame(7))
        dump.close()
        assertTrue("the writer finishes on its own", dump.awaitFinished(5_000))

        assertEquals(3L, dump.written)
        assertEquals(0L, dump.dropped)
        assertEquals(Wav.HEADER_BYTES + 3 * frame * 2, file.length().toInt())
        assertEquals("the header is rewritten at close", 3 * frame * 2, dataLength(file))
        val s = samples(file)
        assertEquals(3 * frame, s.size)
        assertEquals(1.toShort(), s[0])
        assertEquals("the extreme sample survives the byte order", (-32_768).toShort(), s[frame])
        assertEquals(7.toShort(), s[2 * frame])
    }

    @Test
    fun `a dump nobody ever offered a frame to is still a valid empty file`() {
        val file = File(tmp.newFolder(), "capture.wav")
        val dump = PcmDump(file, rate, frame)
        dump.start()
        dump.close()
        assertTrue(dump.awaitFinished(5_000))
        assertEquals(Wav.HEADER_BYTES.toLong(), file.length())
        assertEquals(0, dataLength(file))
    }

    @Test
    fun `the file stops growing at the cap and says so once`() {
        val file = File(tmp.newFolder(), "capture.wav")
        val lines = mutableListOf<String>()
        // Two frames' worth: the third has nowhere to go.
        val dump = PcmDump(file, rate, frame, maxBytes = 2L * frame * 2, log = { lines += it })
        dump.start()
        repeat(5) { dump.offer(frame(3)) }
        dump.close()
        assertTrue(dump.awaitFinished(5_000))

        assertEquals(2L, dump.written)
        assertEquals("capped frames are not dropped frames: the loop handed them over", 0L, dump.dropped)
        assertEquals(Wav.HEADER_BYTES + 2 * frame * 2, file.length().toInt())
        assertEquals(1, lines.count { it.contains("capped") })
    }

    @Test
    fun `a frame the writer has no buffer for is dropped, never waited for`() {
        val file = File(tmp.newFolder(), "capture.wav")
        // The writer is never started, so nothing is ever returned to the pool: the queue fills and
        // stays full. This is the phone's case of a stalled write, made deterministic.
        val dump = PcmDump(file, rate, frame, queueFrames = 4, runWriter = { })
        dump.start()
        repeat(10) { dump.offer(frame(1)) }
        assertEquals("six frames had nowhere to go", 6L, dump.dropped)
        assertEquals("and the capture thread never blocked for any of them", 0L, dump.written)
    }

    @Test
    fun `a frame of the wrong length is dropped rather than resized`() {
        val file = File(tmp.newFolder(), "capture.wav")
        val dump = PcmDump(file, rate, frame)
        dump.start()
        dump.offer(ShortArray(frame - 1))
        dump.offer(frame(9))
        dump.close()
        assertTrue(dump.awaitFinished(5_000))
        assertEquals(1L, dump.written)
        assertEquals(1L, dump.dropped)
    }

    @Test
    fun `offering before start or after close writes nothing and throws nothing`() {
        val file = File(tmp.newFolder(), "capture.wav")
        val dump = PcmDump(file, rate, frame)
        dump.offer(frame(1)) // before start: no file, no pool, no complaint
        assertFalse(file.exists())
        dump.start()
        dump.offer(frame(2))
        dump.close()
        dump.offer(frame(3))
        assertTrue(dump.awaitFinished(5_000))
        assertEquals(1L, dump.written)
    }

    @Test
    fun `the log line is what the bench reads`() {
        val file = File(tmp.newFolder(), "capture-20260920-143012.wav")
        val lines = mutableListOf<String>()
        val dump = PcmDump(file, rate, frame, log = { lines += it })
        dump.start()
        repeat(50) { dump.offer(frame(1)) } // 50 frames x 20 ms = 1 s
        dump.close()
        assertTrue(dump.awaitFinished(5_000))

        assertEquals("capture dump: writing ${file.path}", lines.first())
        assertEquals(
            "capture dump: capture-20260920-143012.wav, 50 frames, 1.0 s, 32000 bytes, 0 dropped",
            lines.last(),
        )
    }
}
