package com.kivan.motoparty.music

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Rewrites the `dOps` box of an Opus MP4 so that every player reads it alike. Pure JVM.
 *
 * `dOps` is big-endian (Opus in ISOBMFF §4.3.2) and AVPlayer reads it so; the OpusHead it is made
 * from is little-endian. Media3 1.11.1 copies the OpusHead fields across unswapped, and its own MP4
 * extractor reads them back unswapped, so ExoPlayer gets them right and AVPlayer does not: the
 * 312-sample pre-skip reads as 14 337 there (0x0138 → 0x3801). The iPhone would lose the first
 * ~300 ms of every track and play ~290 ms apart from the Pixel, with both reporting the same
 * position, so drift correction would never see it (found on the Pixel, 2026-09-24).
 *
 * PreSkip is written as 0, the one value both byte orders agree on: both decoders then also play
 * the ~6.5 ms of pre-roll that pre-skip would have hidden, at the very start of the track, and stay
 * sample-aligned. InputSampleRate and OutputGain are written big-endian from [opusHead], the WebM's
 * csd-0 (no decoder plays at the former; YouTube's gain is 0).
 */
fun patchOpusDops(mp4: File, opusHead: ByteArray) {
    if (opusHead.size < OPUS_HEAD_SIZE || String(opusHead, 0, 8, Charsets.US_ASCII) != "OpusHead") {
        throw IOException("not an OpusHead (${opusHead.size} bytes)")
    }
    val channels = opusHead[9]
    val inputRate = le32(opusHead, 12)
    val gain = le16(opusHead, 16)
    RandomAccessFile(mp4, "rw").use { f ->
        val dops = findBox(f, 0, f.length(), DOPS_PATH) ?: throw IOException("no dOps box in ${mp4.name}")
        f.seek(dops.payload)
        val head = ByteArray(DOPS_FIXED_SIZE).also { f.readFully(it) }
        if (dops.end - dops.payload < DOPS_FIXED_SIZE || head[1] != channels) {
            throw IOException("unexpected dOps in ${mp4.name}: ${head.joinToString(" ") { "%02x".format(it) }}")
        }
        f.seek(dops.payload + 2)
        f.writeShort(0) // PreSkip
        f.writeInt(inputRate)
        f.writeShort(gain)
    }
}

private class Box(val payload: Long, val end: Long)

/** The box at [path] inside [start, end), descending through each container on the way. */
private fun findBox(f: RandomAccessFile, start: Long, end: Long, path: List<String>): Box? {
    var at = start
    while (at + 8 <= end) {
        f.seek(at)
        var size = f.readInt().toLong() and 0xFFFFFFFFL
        val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
        var header = 8L
        if (size == 1L) {
            size = f.readLong()
            header = 16
        } else if (size == 0L) {
            size = end - at
        }
        if (size < header || at + size > end) return null
        if (type == path[0]) {
            val payload = at + header
            if (path.size == 1) return Box(payload, at + size)
            // stsd is a full box with an entry count; an audio sample entry has 28 bytes of fields.
            val children = payload + when (type) {
                "stsd" -> 8
                "Opus" -> 28
                else -> 0
            }
            return findBox(f, children, at + size, path.drop(1))
        }
        at += size
    }
    return null
}

private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

private val DOPS_PATH = listOf("moov", "trak", "mdia", "minf", "stbl", "stsd", "Opus", "dOps")
/** "OpusHead" + version, channels, pre-skip, input rate, gain, mapping family. */
private const val OPUS_HEAD_SIZE = 19
/** Version, channels, pre-skip, input rate, gain, mapping family. */
private const val DOPS_FIXED_SIZE = 11
