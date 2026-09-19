package com.kivan.motoparty.music

import com.kivan.motoparty.core.QueueItem

/** A YouTube video id plus the metadata the protocol carries. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
) {
    val path: String get() = "/track/$id.m4a"
    fun toQueueItem() = QueueItem(id, title, artist)
}

/** YouTube ids are [A-Za-z0-9_-]; anything else must never reach the file system or a URL. */
fun isValidTrackId(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }
