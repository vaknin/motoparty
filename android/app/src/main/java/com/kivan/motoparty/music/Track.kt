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

/** An artist from a search (2026-10-02): [id] is a YouTube channel id (`UC…`). */
data class ArtistItem(val id: String, val name: String, val art: String? = null)

/**
 * An artist's page (PROTOCOL.md "Browsing" step 2a): the name and picture from the channel, its
 * top [songs] (at most [Catalog.ARTIST_SONGS]) and its [albums] and singles (at most
 * [Catalog.ARTIST_ALBUMS]); either may be empty.
 */
data class ArtistPage(
    val name: String,
    val art: String?,
    val songs: List<Track>,
    val albums: List<CollectionItem>,
    /** The albums came from a Releases tab, not the album-search fallback (for the log). */
    val releases: Boolean = false,
)

/**
 * YouTube video and playlist ids are [A-Za-z0-9_-]; anything else must never reach the file
 * system or a URL.
 */
fun isValidTrackId(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
