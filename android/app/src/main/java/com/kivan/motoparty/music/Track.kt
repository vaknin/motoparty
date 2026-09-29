package com.kivan.motoparty.music

import com.kivan.motoparty.core.QueueItem
import kotlinx.serialization.Serializable

/** A YouTube video id plus the metadata the protocol carries. Serializable for [History]. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
    /** Cover image URL, when the catalog had one. */
    val art: String? = null,
) {
    val path: String get() = "/track/$id.m4a"
    fun toQueueItem() = QueueItem(id, title, artist)
}

/** An album or playlist from a search: [id] is a YouTube playlist id. [count] is null when unknown. */
data class CollectionItem(
    val id: String,
    val title: String,
    val artist: String,
    val count: Int? = null,
    val art: String? = null,
)

/**
 * YouTube video and playlist ids are [A-Za-z0-9_-]; anything else must never reach the file
 * system or a URL.
 */
fun isValidTrackId(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
