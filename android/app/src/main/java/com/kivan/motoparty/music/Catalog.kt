package com.kivan.motoparty.music

import com.kivan.motoparty.core.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
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
import java.io.IOException

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
            Command.Kind.ALBUM -> topCollection(albums = true, query, "album")
            Command.Kind.PLAYLIST -> topCollection(albums = false, query, "playlist")
        }
    }

    /** Free-text song search for the search screens: YouTube Music songs. */
    suspend fun searchSongs(query: String, limit: Int = 20): List<Track> =
        withContext(Dispatchers.IO) { songs(query).take(limit) }

    /** Albums ([albums]) or playlists matching [query], for the search screens. */
    suspend fun searchCollections(albums: Boolean, query: String, limit: Int = 20): List<CollectionItem> =
        withContext(Dispatchers.IO) {
            val filter = if (albums) Q.MUSIC_ALBUMS else Q.MUSIC_PLAYLISTS
            val hits = runSearch(query, filter).filterIsInstance<PlaylistInfoItem>()
                .ifEmpty { runSearch(query, Q.PLAYLISTS).filterIsInstance<PlaylistInfoItem>() }
            hits.mapNotNull { it.toCollection() }.take(limit)
        }

    /** The tracks of the album or playlist [id], in order, at most [MAX_COLLECTION]. */
    suspend fun browse(id: String): List<Track> = withContext(Dispatchers.IO) { tracksOf(id).second }

    private fun songs(query: String): List<Track> {
        val items = runSearch(query, Q.MUSIC_SONGS).filterIsInstance<StreamInfoItem>()
        val fromMusic = items.mapNotNull { it.toTrack(album = null, art = null) }
        if (fromMusic.isNotEmpty()) return fromMusic
        // Plain YouTube as a fallback when the Music endpoint returns nothing or breaks.
        return runSearch(query, Q.VIDEOS).filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(null, null) }
    }

    private fun topCollection(albums: Boolean, query: String, word: String): Result {
        val filter = if (albums) Q.MUSIC_ALBUMS else Q.MUSIC_PLAYLISTS
        val hit = (runSearch(query, filter).filterIsInstance<PlaylistInfoItem>().firstNotNullOfOrNull { it.toCollection() }
            ?: runSearch(query, Q.PLAYLISTS).filterIsInstance<PlaylistInfoItem>().firstNotNullOfOrNull { it.toCollection() })
            ?: throw NotFound(query)
        val (name, tracks) = tracksOf(hit.id, hit.art)
        if (tracks.isEmpty()) throw NotFound(query)
        val by = hit.artist.takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""
        return Result(tracks, "$word $name$by")
    }

    /**
     * The collection's name and tracks. Every track gets the collection's cover ([art], or the
     * playlist's own), which for an album is the right picture and saves a URL per track.
     */
    private fun tracksOf(id: String, art: String? = null): Pair<String, List<Track>> {
        require(isValidTrackId(id)) { "bad playlist id" }
        val url = "https://www.youtube.com/playlist?list=$id"
        val info = PlaylistInfo.getInfo(yt, url)
        val name = cleanAlbum(info.name ?: id)
        val cover = art ?: bestImage(info.thumbnails)
        val items = ArrayList<StreamInfoItem>(info.relatedItems)
        var page = info.nextPage
        while (page != null && items.size < MAX_COLLECTION) {
            val more = PlaylistInfo.getMoreItems(yt, url, page)
            items += more.items
            page = more.nextPage
        }
        return name to items.take(MAX_COLLECTION).mapNotNull { it.toTrack(album = name, art = cover) }
    }

    private fun runSearch(query: String, filter: String): List<InfoItem> = try {
        SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(query, listOf(filter), "")).relatedItems
    } catch (e: Exception) {
        // No network is an answer in itself ("No coverage"); a broken endpoint falls back.
        if (e is InterruptedException || e is IOException) throw e
        emptyList()
    }

    private fun StreamInfoItem.toTrack(album: String?, art: String?): Track? {
        val id = runCatching { yt.streamLHFactory.getId(url) }.getOrNull() ?: return null
        if (!isValidTrackId(id)) return null
        return Track(
            id = id,
            title = name ?: id,
            artist = cleanArtist(uploaderName ?: ""),
            album = album,
            durationMs = if (duration > 0) duration * 1000 else 0,
            art = art ?: bestImage(thumbnails),
        )
    }

    private fun PlaylistInfoItem.toCollection(): CollectionItem? {
        val id = runCatching { yt.playlistLHFactory.getId(url) }.getOrNull() ?: return null
        if (!isValidTrackId(id)) return null
        return CollectionItem(
            id = id,
            title = cleanAlbum(name ?: id),
            artist = cleanArtist(uploaderName ?: ""),
            count = streamCount.takeIf { it > 0 }?.toInt(),
            art = bestImage(thumbnails),
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

        /**
         * The smallest image at least [ART_PX] wide, else the largest: enough for a phone
         * screen, and the URL is what goes on the wire. Song results list only 60 and 120 px,
         * but their googleusercontent URLs take the size as a parameter, so those get asked
         * for [RESIZED_PX].
         */
        fun bestImage(images: List<Image>): String? {
            val known = images.filter { it.width > 0 }
            val big = known.filter { it.width >= ART_PX }.minByOrNull { it.width }
            if (big != null) return big.url
            val url = (known.maxByOrNull { it.width } ?: images.lastOrNull())?.url ?: return null
            return if (RESIZABLE.containsMatchIn(url)) url.replace(SIZE, "=w$RESIZED_PX-h$RESIZED_PX") else url
        }

        private const val ART_PX = 300
        private const val RESIZED_PX = 544
        private val RESIZABLE = Regex("""^https://(yt3|lh3)\.(googleusercontent|ggpht)\.com/[^?]*=w\d+-h\d+""")
        private val SIZE = Regex("""=w\d+-h\d+""")

        fun cleanAlbum(name: String): String = name.removePrefix("Album – ").removePrefix("Album - ")

        /** Auto-generated YouTube Music channels are named "Artist - Topic". */
        fun cleanArtist(name: String): String = name.removeSuffix(" - Topic").trim()
    }
}
