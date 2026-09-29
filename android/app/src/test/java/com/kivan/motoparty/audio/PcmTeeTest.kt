package com.kivan.motoparty.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class PcmTeeTest {
    private val frame = 320

    /** The writer never runs: the queue fills, and every frame after that is dropped, not waited for. */
    @Test
    fun neverBlocksAndDropsWhenFull() {
        val tee = PcmTee(ByteArrayOutputStream(), frame, queueFrames = 10, runWriter = {})
        tee.start()
        val pcm = ShortArray(frame)
        val from = System.nanoTime()
        repeat(1_000) { tee.offer(pcm) }
        val ms = (System.nanoTime() - from) / 1_000_000
        assertEquals(990L, tee.dropped)
        assertTrue("1000 offers took $ms ms", ms < 500)
    }

    /** A reader that never reads (a stalled recognizer) blocks only the writer. */
    @Test
    fun aBlockedPipeOnlyBlocksTheWriter() {
        val stuck = object : OutputStream() {
            override fun write(b: Int) = Thread.sleep(Long.MAX_VALUE)
            override fun write(b: ByteArray, off: Int, len: Int) = Thread.sleep(Long.MAX_VALUE)
        }
        val tee = PcmTee(stuck, frame, queueFrames = 5)
        tee.start()
        val from = System.nanoTime()
        repeat(100) { tee.offer(ShortArray(frame)) }
        val ms = (System.nanoTime() - from) / 1_000_000
        assertTrue("100 offers took $ms ms", ms < 500)
        assertTrue("dropped ${tee.dropped}", tee.dropped >= 94)
    }

    @Test
    fun writesLittleEndianPcmAndClosesTheStreamOnClose() {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }
        val tee = PcmTee(out, 2, queueFrames = 4)
        tee.start()
        tee.offer(shortArrayOf(1, -2))
        tee.offer(shortArrayOf(0x1234, 0x7fff))
        tee.close()
        assertTrue(tee.awaitFinished(2_000))
        assertTrue("EOF for the recognizer", closed)
        assertEquals(2L, tee.written)
        assertEquals(0L, tee.dropped)
        assertArrayEquals(
            byteArrayOf(1, 0, 0xfe.toByte(), 0xff.toByte(), 0x34, 0x12, 0xff.toByte(), 0x7f),
            out.toByteArray(),
        )
    }

    @Test
    fun wrongSizedFramesAreDroppedAndClosedTeeIgnoresFrames() {
        val tee = PcmTee(ByteArrayOutputStream(), frame, queueFrames = 4, runWriter = {})
        tee.offer(ShortArray(frame)) // not started: ignored, not counted
        tee.start()
        tee.offer(ShortArray(frame - 1))
        assertEquals(1L, tee.dropped)
        tee.close()
        tee.offer(ShortArray(frame))
        assertEquals(1L, tee.dropped)
    }

    /** The recognizer went away: the writer ends, and the capture thread is not told. */
    @Test
    fun aBrokenPipeEndsTheWriterQuietly() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("EPIPE")
        }
        val lines = mutableListOf<String>()
        val tee = PcmTee(broken, 2, queueFrames = 4, log = { synchronized(lines) { lines += it } })
        tee.start()
        tee.offer(shortArrayOf(1, 2))
        assertTrue(tee.awaitFinished(2_000))
        tee.offer(shortArrayOf(1, 2)) // after the failure: a no-op, never a throw
        assertTrue(synchronized(lines) { lines.any { "pipe closed" in it } })
    }
}
