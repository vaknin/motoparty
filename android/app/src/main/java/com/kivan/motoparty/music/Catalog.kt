package com.kivan.motoparty.music

import com.kivan.motoparty.core.Command
import com.kivan.motoparty.core.CommandParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
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
import kotlin.random.Random

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

    /** Artists matching [query] (2026-10-02), YouTube Music's artist results, for the search screens. */
    suspend fun searchArtists(query: String, limit: Int = 20): List<ArtistItem> = withContext(Dispatchers.IO) {
        runSearch(query, Q.MUSIC_ARTISTS).filterIsInstance<ChannelInfoItem>().mapNotNull { it.toArtist() }
            .distinctBy { it.id }.take(limit)
    }

    /**
     * The artist page of channel [id] (PROTOCOL.md "Browsing" step 2a). One request for the
     * channel (its name, when the caller has none, and its picture), then in parallel: the
     * artist's Releases ([artistReleases]) and a song search for the name. The songs are kept to
     * that artist ([artistSongs]); without Releases, an album search for the name kept to that
     * artist's albums stands in ([artistAlbums]). The official channel's names (often
     * "ישי ריבו | Ishay Ribo") count as the artist's too, for both filters.
     */
    suspend fun artistPage(id: String, name: String? = null): ArtistPage = withContext(Dispatchers.IO) {
        require(isValidTrackId(id)) { "bad channel id" }
        val info = ChannelInfo.getInfo(yt, yt.channelLHFactory.getUrl("channel/$id"))
        val artist = name?.takeIf { it.isNotBlank() } ?: cleanArtist(info.name ?: "")
        if (artist.isBlank()) throw NotFound(id)
        coroutineScope {
            val releases = async { artistReleases(info, artist) }
            val found = async { songs(artist) }
            val (albums, official) = releases.await()
            val names = (listOf(artist) + (official?.name?.let(::channelNames) ?: emptyList())).distinct()
            // The official channel's spelling ("Pink Floyd") reads better than YouTube Music's ("PINK FLOYD").
            val shown = names.drop(1).firstOrNull { CommandParser.normalize(it) == CommandParser.normalize(artist) } ?: artist
            val page = artistAlbums(names, albums.map { it.copy(artist = shown) }) {
                runSearch(artist, Q.MUSIC_ALBUMS).filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toCollection() }
            }
            ArtistPage(shown, bestImage(info.avatars), artistSongs(found.await(), names), page, releases = albums.isNotEmpty())
        }
    }

    /**
     * The artist's "Releases" tab ([ChannelTabs.ALBUMS]) and the channel it came from: the full
     * discography in YouTube's order, which an album search only samples. YouTube Music's artist
     * results are the auto-generated "Artist - Topic" channels, which have no tabs at all (seen
     * 2026-10-02), so without one on [info] the official artist channel is looked up by name
     * ([channelNames]). Only official artist channels have a Releases tab, so a fan channel of
     * the same name has nothing to offer here. Empty when none has one.
     */
    private fun artistReleases(info: ChannelInfo, artist: String): Pair<List<CollectionItem>, ChannelInfo?> {
        releasesOf(info).let { if (it.isNotEmpty()) return it to info }
        val name = CommandParser.normalize(artist)
        val official = runSearch(artist, Q.CHANNELS).filterIsInstance<ChannelInfoItem>()
            .filter { c ->
                val n = c.name ?: ""
                !n.endsWith(TOPIC) && c.url != info.url && channelNames(n).any { CommandParser.normalize(it) == name }
            }
            .take(OFFICIAL_PROBES)
        for (channel in official) {
            val other = try {
                ChannelInfo.getInfo(yt, channel.url)
            } catch (e: Exception) {
                if (e is InterruptedException || e is IOException) throw e
                continue
            }
            releasesOf(other).let { if (it.isNotEmpty()) return it to other }
        }
        return emptyList<CollectionItem>() to null
    }

    /** The channel's "Releases" tab as collections, at most [ARTIST_ALBUMS]; empty without one or on a parse error. */
    private fun releasesOf(info: ChannelInfo): List<CollectionItem> {
        val tab = info.tabs.firstOrNull { ChannelTabs.ALBUMS in it.contentFilters } ?: return emptyList()
        return try {
            val first = ChannelTabInfo.getInfo(yt, tab)
            val items = ArrayList(first.relatedItems)
            var page = first.nextPage
            while (page != null && items.size < ARTIST_ALBUMS) {
                val more = ChannelTabInfo.getMoreItems(yt, tab, page)
                items += more.items
                page = more.nextPage
            }
            items.filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toCollection() }
        } catch (e: Exception) {
            if (e is InterruptedException || e is IOException) throw e
            emptyList()
        }
    }

    /** The tracks of the album or playlist [id], in order, at most [MAX_COLLECTION]. */
    suspend fun browse(id: String): List<Track> = withContext(Dispatchers.IO) { tracksOf(id).second }

    /**
     * Music like the track [id], without the track itself: YouTube Music's radio for it, else
     * the plain YouTube mix. Empty when YouTube has neither.
     */
    suspend fun similar(id: String): List<Track> = withContext(Dispatchers.IO) {
        require(isValidTrackId(id)) { "bad track id" }
        for (mix in listOf("RDAMVM$id", "RD$id")) {
            val tracks = try {
                PlaylistInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id&list=$mix").relatedItems
                    .mapNotNull { it.toTrack(album = null, art = null) }.filter { it.id != id }
            } catch (e: Exception) {
                if (e is InterruptedException || e is IOException) throw e
                emptyList()
            }
            if (tracks.isNotEmpty()) return@withContext tracks
        }
        emptyList()
    }

    /**
     * The album that holds [track]: the artist's albums found for "<artist> <title>" and then for
     * "<artist>", at most [ALBUM_PROBES] of them browsed, the first whose track list has the track
     * (same id or same title). Null when none does. For "the rest of this album" when the album
     * the interpreter named does not hold the playing track (it guessed from its own knowledge).
     */
    suspend fun albumContaining(track: Track): Result? = withContext(Dispatchers.IO) {
        val artist = CommandParser.normalize(cleanArtist(track.artist))
        val seen = mutableSetOf<String>()
        var probes = 0
        for (query in listOf("${track.artist} ${track.title}", track.artist)) {
            val albums = searchCollections(albums = true, query)
                .filter { CommandParser.normalize(cleanArtist(it.artist)) == artist && seen.add(it.id) }
            for (album in albums) {
                if (probes++ >= ALBUM_PROBES) return@withContext null
                val tracks = tracksOf(album.id).second
                if (VoiceQueue.holds(tracks, track)) return@withContext Result(tracks, "album ${album.title} by ${album.artist}")
            }
        }
        null
    }

    private fun songs(query: String): List<Track> {
        val items = runSearch(query, Q.MUSIC_SONGS).filterIsInstance<StreamInfoItem>()
        val fromMusic = items.mapNotNull { it.toTrack(album = null, art = null) }
        if (fromMusic.isNotEmpty()) return fromMusic
        // Plain YouTube as a fallback when the Music endpoint returns nothing or breaks.
        return runSearch(query, Q.VIDEOS).filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(null, null) }
    }

    private fun topCollection(albums: Boolean, query: String, word: String): Result {
        val filter = if (albums) Q.MUSIC_ALBUMS else Q.MUSIC_PLAYLISTS
        val hits = runSearch(query, filter).filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toCollection() }
        val hit = (if (albums) anyAlbum(hits, query) else null) ?: hits.firstOrNull()
            ?: runSearch(query, Q.PLAYLISTS).filterIsInstance<PlaylistInfoItem>().firstNotNullOfOrNull { it.toCollection() }
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

    private fun ChannelInfoItem.toArtist(): ArtistItem? {
        // The channel factory's id is "channel/UC…"; only the UC… part travels (PROTOCOL.md 2a).
        val id = runCatching { yt.channelLHFactory.getId(url) }.getOrNull()?.removePrefix("channel/") ?: return null
        if (!isValidTrackId(id)) return null
        return ArtistItem(id = id, name = cleanArtist(name ?: return null), art = bestImage(thumbnails))
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

        /** An artist page's limits, PROTOCOL.md "Browsing" step 2a. */
        const val ARTIST_SONGS = 20
        const val ARTIST_ALBUMS = 50

        /** Same-named channels checked for a Releases tab when the artist's own channel has none. */
        private const val OFFICIAL_PROBES = 3
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

        /**
         * "Any album by X" (PROTOCOL.md "Commands", *The clarifying question*): when [query] is
         * the artist of album results and the title of none, one of that artist's top
         * [ANY_ALBUM_TOP] results at random; null when the query names an album as usual.
         */
        fun anyAlbum(hits: List<CollectionItem>, query: String, random: Random = Random): CollectionItem? {
            val q = CommandParser.normalize(query)
            if (hits.any { CommandParser.normalize(it.title) == q }) return null
            return hits.filter { CommandParser.normalize(it.artist) == q }.take(ANY_ALBUM_TOP).randomOrNull(random)
        }

        const val ANY_ALBUM_TOP = 5

        /**
         * The names in a credit (2026-10-02): "A, B & C feat. D" → [A, B, C, D]. Splits on ", ",
         * " & ", " feat."/" ft."/" featuring " (any case) and a lowercase " x " (the
         * collaboration "A x B"; a capital X is part of names like "Malcolm X"). The whole
         * credit is not among them: callers that want it ("Simon & Garfunkel" is one artist)
         * try it first.
         */
        fun splitArtists(credit: String): List<String> =
            credit.split(CREDIT_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

        private val CREDIT_SEPARATORS = Regex("""\s*,\s+|\s+&\s+|\s+(?i:feat\.?|ft\.|featuring)\s+|\s+x\s+""")

        /**
         * The artist a credit means among [hits] (2026-10-02, the Ride screen's artist tap): the
         * first hit named like the whole credit, else like its first name, then its later names;
         * null when none is (the caller takes the top hit). Names compare normalised.
         */
        fun pickArtist(hits: List<ArtistItem>, credit: String): ArtistItem? {
            val wanted = (listOf(credit) + splitArtists(credit)).map(CommandParser::normalize).distinct()
            for (w in wanted) hits.firstOrNull { CommandParser.normalize(it.name) == w }?.let { return it }
            return null
        }

        /**
         * The names a channel goes by (2026-10-02): "ישי ריבו | Ishay Ribo" → both,
         * "Queen Official" and "QueenVEVO" → "Queen", "Moby - Topic" → "Moby".
         */
        fun channelNames(channel: String): List<String> =
            channel.split('|').map { cleanArtist(it).replace(CHANNEL_SUFFIX, "").trim() }.filter { it.isNotEmpty() }

        private const val TOPIC = " - Topic"
        private val CHANNEL_SUFFIX = Regex("""(?i)\s*vevo$|\s+official(\s+channel)?$""")

        /**
         * An artist page's albums (2026-10-02): the channel's [releases] when it has any, else
         * [search] (an album search for the name, only run then) kept to albums credited to one
         * of the artist's [names] (normalised, "- Topic" dropped, as [anyAlbum] compares).
         * Distinct, at most [ARTIST_ALBUMS].
         */
        fun artistAlbums(names: List<String>, releases: List<CollectionItem>, search: () -> List<CollectionItem>): List<CollectionItem> {
            val wanted = names.map(CommandParser::normalize).toSet()
            val albums = releases.ifEmpty {
                search().filter { CommandParser.normalize(cleanArtist(it.artist)) in wanted }
            }
            return albums.distinctBy { it.id }.take(ARTIST_ALBUMS)
        }

        /**
         * An artist page's top songs (2026-10-02): [songs] (a song search for the name) kept to
         * those credited to one of the artist's [names], alone or among others; when that leaves
         * none (YouTube spelled the name differently), the search's own top songs. At most
         * [ARTIST_SONGS].
         */
        fun artistSongs(songs: List<Track>, names: List<String>): List<Track> {
            val wanted = names.map(CommandParser::normalize).toSet()
            val theirs = songs.filter { s ->
                CommandParser.normalize(s.artist) in wanted || splitArtists(s.artist).any { CommandParser.normalize(it) in wanted }
            }
            return theirs.ifEmpty { songs }.distinctBy { it.id }.take(ARTIST_SONGS)
        }

        /** Albums browsed at most by [albumContaining]: each is one request (about 0.5 s). */
        const val ALBUM_PROBES = 4

        fun cleanAlbum(name: String): String = name.removePrefix("Album – ").removePrefix("Album - ")

        /** Auto-generated YouTube Music channels are named "Artist - Topic". */
        fun cleanArtist(name: String): String = name.removeSuffix(" - Topic").trim()
    }
}
