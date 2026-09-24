package com.kivan.motoparty.music

import com.kivan.motoparty.core.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as Q
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube Music search and audio resolution through NewPipeExtractor. YouTube does the fuzzy
 * matching; we take its top hit. Pure JVM (runs in unit tests with -Pnetwork).
 */
class Catalog(private val http: OkHttpClient) {
    /** What a `play <kind> <query>` resolved to: a queue, plus a label for the announcement. */
    data class Result(val tracks: List<Track>, val label: String)

    class NotFound(query: String) : Exception("nothing found for \"$query\"")

    init {
        initNewPipe(http)
    }

    private val yt get() = ServiceList.YouTube

    suspend fun search(kind: Command.Kind, query: String): Result = withContext(Dispatchers.IO) {
        when (kind) {
            Command.Kind.SONG -> {
                val t = songs(query).firstOrNull() ?: throw NotFound(query)
                Result(listOf(t), "${t.title} by ${t.artist}")
            }
            Command.Kind.ARTIST -> {
                val list = songs(query).take(ARTIST_TRACKS)
                if (list.isEmpty()) throw NotFound(query)
                Result(list, "songs by ${list.first().artist}")
            }
            Command.Kind.ALBUM -> collection(Q.MUSIC_ALBUMS, query, "album")
            Command.Kind.PLAYLIST -> collection(Q.MUSIC_PLAYLISTS, query, "playlist")
        }
    }

    /** Free-text search for the manual search box: YouTube Music songs. */
    suspend fun searchSongs(query: String, limit: Int = 20): List<Track> =
        withContext(Dispatchers.IO) { songs(query).take(limit) }

    private fun songs(query: String): List<Track> {
        val items = runSearch(query, Q.MUSIC_SONGS).filterIsInstance<StreamInfoItem>()
        val fromMusic = items.mapNotNull { it.toTrack(album = null) }
        if (fromMusic.isNotEmpty()) return fromMusic
        // Plain YouTube as a fallback when the Music endpoint returns nothing or breaks.
        return runSearch(query, Q.VIDEOS).filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(null) }
    }

    private fun collection(filter: String, query: String, word: String): Result {
        val hit = runSearch(query, filter).filterIsInstance<PlaylistInfoItem>().firstOrNull()
            ?: runSearch(query, Q.PLAYLISTS).filterIsInstance<PlaylistInfoItem>().firstOrNull()
            ?: throw NotFound(query)
        val info = PlaylistInfo.getInfo(yt, hit.url)
        val name = (info.name ?: hit.name).removePrefix("Album – ").removePrefix("Album - ")
        val items = ArrayList<StreamInfoItem>(info.relatedItems)
        var page = info.nextPage
        while (page != null && items.size < MAX_COLLECTION) {
            val more = PlaylistInfo.getMoreItems(yt, hit.url, page)
            items += more.items
            page = more.nextPage
        }
        val tracks = items.take(MAX_COLLECTION).mapNotNull { it.toTrack(album = name) }
        if (tracks.isEmpty()) throw NotFound(query)
        val by = hit.uploaderName?.takeIf { it.isNotBlank() }?.let { " by ${cleanArtist(it)}" } ?: ""
        return Result(tracks, "$word $name$by")
    }

    private fun runSearch(query: String, filter: String): List<InfoItem> = try {
        SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(query, listOf(filter), "")).relatedItems
    } catch (e: Exception) {
        if (e is InterruptedException) throw e
        emptyList()
    }

    private fun StreamInfoItem.toTrack(album: String?): Track? {
        val id = runCatching { yt.streamLHFactory.getId(url) }.getOrNull() ?: return null
        if (!isValidTrackId(id)) return null
        return Track(
            id = id,
            title = name ?: id,
            artist = cleanArtist(uploaderName ?: ""),
            album = album,
            durationMs = if (duration > 0) duration * 1000 else 0,
        )
    }

    /**
     * A direct URL for the best audio-only stream. Without Premium YouTube offers two: Opus in
     * WebM (itag 251, ~130 kbps VBR, full band) and AAC-LC in MP4 (itag 140, ~130 kbps, cut at
     * ~16 kHz). Opus is preferred ([opus]) and falls back to AAC; a WebM result has to be remuxed
     * to MP4 before a client can play it ([ResolvedAudio.webm]). Nothing lossless exists.
     */
    suspend fun resolveAudio(id: String, opus: Boolean = true): ResolvedAudio = withContext(Dispatchers.IO) {
        require(isValidTrackId(id))
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id")
        val progressive = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        fun pick(format: MediaFormat, itag: Int): AudioStream? = progressive.filter { it.format == format }
            .let { c -> c.firstOrNull { it.itag == itag } ?: c.maxByOrNull { it.averageBitrate } }
        val best: AudioStream = (if (opus) pick(MediaFormat.WEBMA_OPUS, 251) else null)
            ?: pick(MediaFormat.M4A, 140)
            ?: error("no progressive audio for $id (${info.audioStreams.map { "${it.format}/${it.deliveryMethod}" }})")
        ResolvedAudio(
            best.content, best.itag, webm = best.format == MediaFormat.WEBMA_OPUS,
            info.duration * 1000, info.name, cleanArtist(info.uploaderName ?: ""),
        )
    }

    data class ResolvedAudio(
        val url: String, val itag: Int, val webm: Boolean,
        val durationMs: Long, val title: String?, val artist: String,
    )

    companion object {
        private const val ARTIST_TRACKS = 20
        private const val MAX_COLLECTION = 200

        @Volatile private var initialised = false

        @Synchronized
        fun initNewPipe(http: OkHttpClient) {
            if (initialised) return
            NewPipe.init(OkHttpDownloader(http), Localization("en", "US"), ContentCountry("US"))
            initialised = true
        }

        /** Auto-generated YouTube Music channels are named "Artist - Topic". */
        fun cleanArtist(name: String): String = name.removeSuffix(" - Topic").trim()
    }
}
