package com.kivan.motoparty.music

import com.kivan.motoparty.core.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Live YouTube tests; skipped unless `./gradlew test -Pnetwork`. */
class CatalogNetworkTest {
    private val http = OkHttpClient()

    @Before fun onlyWithNetwork() = assumeTrue(System.getProperty("motoparty.network") == "true")

    @Test
    fun similarMusicComesFromTheRadioOfATrack(): Unit = runBlocking {
        val catalog = Catalog(http)
        val track = catalog.search(Command.Kind.SONG, "porcelain moby").tracks.first()
        val similar = catalog.similar(track.id)
        println("similar to ${track.title} (${track.id}) -> ${similar.size}: ${similar.take(8).map { "${it.title} – ${it.artist} ${it.durationMs}" }}")
        assertTrue(similar.size >= 5)
        assertTrue(similar.none { it.id == track.id })
    }

    @Test
    fun theAlbumThatHoldsATrackIsFound(): Unit = runBlocking {
        val catalog = Catalog(http)
        val track = catalog.search(Command.Kind.SONG, "porcelain moby").tracks.first()
        val album = catalog.albumContaining(track)
        println("album of ${track.title} – ${track.artist} -> ${album?.label}: ${album?.tracks?.map { it.title }}")
        assertTrue(album != null && VoiceQueue.holds(album.tracks, track))
    }

    @Test
    fun searchResolveAndDownloadASong(): Unit = runBlocking {
        val catalog = Catalog(http)
        val result = catalog.search(Command.Kind.SONG, "bohemian rhapsody queen")
        println("song -> ${result.label}: ${result.tracks}")
        val track = result.tracks.first()
        val opus = catalog.resolveAudio(track.id)
        println("resolved itag ${opus.itag} webm=${opus.webm}, ${opus.url.take(80)}")
        assertEquals(251, opus.itag)
        assertTrue(opus.webm)
        val aac = catalog.resolveAudio(track.id, opus = false)
        assertEquals(140, aac.itag)
        assertFalse(aac.webm)

        val dir = Files.createTempDirectory("tracks").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val aacCache = TrackCache(File(dir, "aac"), http, { TrackCache.Source(aac.url, aac.webm) }, scope)
        val start = System.nanoTime()
        val file = aacCache.ensure(track.id)
        val head = file.readBytes().copyOfRange(4, 8).toString(Charsets.US_ASCII)
        println("downloaded ${file.length()} bytes in ${(System.nanoTime() - start) / 1_000_000} ms, box '$head'")
        assertTrue(file.length() > 500_000)
        assertTrue(head == "ftyp")

        // The real remuxer needs Android's MediaExtractor; here it only has to receive the WebM.
        var remuxed: ByteArray? = null
        val opusCache = TrackCache(File(dir, "opus"), http, { TrackCache.Source(opus.url, opus.webm) }, scope,
            remux = { webm, mp4 -> remuxed = webm.readBytes(); webm.copyTo(mp4) })
        val webm = opusCache.ensure(track.id)
        println("downloaded ${webm.length()} bytes of WebM Opus")
        assertTrue(webm.length() > 500_000)
        assertArrayEquals(EBML_MAGIC, remuxed!!.copyOfRange(0, 4))
        assertEquals(listOf("${track.id}.m4a"), File(dir, "opus").list()!!.toList())
        dir.deleteRecursively()
    }

    private companion object {
        val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    }

    @Test
    fun albumPlaylistArtist(): Unit = runBlocking {
        val catalog = Catalog(http)
        for ((kind, q) in listOf(
            Command.Kind.ALBUM to "dark side of the moon",
            Command.Kind.ARTIST to "queen",
            Command.Kind.PLAYLIST to "road trip",
        )) {
            val r = catalog.search(kind, q)
            println("$kind '$q' -> ${r.label}: ${r.tracks.size} tracks, first ${r.tracks.first()}")
            assertTrue(r.tracks.isNotEmpty())
        }
    }

    @Test
    fun searchAlbumsAndBrowseOne(): Unit = runBlocking {
        val catalog = Catalog(http)
        val albums = catalog.searchCollections(albums = true, "dark side of the moon")
        albums.take(5).forEach { println("album $it") }
        assertTrue(albums.isNotEmpty())
        for (a in albums) {
            assertTrue(isValidTrackId(a.id))
            assertTrue(a.title.isNotBlank())
            assertTrue("art for ${a.id}", a.art?.startsWith("https://") == true)
        }
        val tracks = catalog.browse(albums.first().id)
        tracks.forEach { println("  track $it") }
        assertTrue(tracks.size >= 5)
        assertTrue(tracks.all { isValidTrackId(it.id) && it.title.isNotBlank() })
        assertTrue(tracks.all { it.art?.startsWith("https://") == true })
        // In order: the album opens with "Speak to Me", then "Breathe".
        assertTrue(tracks[0].title, tracks[0].title.contains("Speak to Me", ignoreCase = true))
        assertTrue(tracks[1].title, tracks[1].title.contains("Breathe", ignoreCase = true))
    }

    @Test
    fun searchPlaylists(): Unit = runBlocking {
        val lists = Catalog(http).searchCollections(albums = false, "road trip")
        lists.take(5).forEach { println("playlist $it") }
        assertTrue(lists.isNotEmpty())
        assertTrue(lists.all { isValidTrackId(it.id) && it.title.isNotBlank() && it.art?.startsWith("https://") == true })
    }

    @Test
    fun songsCarryArt(): Unit = runBlocking {
        val songs = Catalog(http).searchSongs("bohemian rhapsody", limit = 5)
        songs.forEach { println("song $it") }
        assertTrue(songs.isNotEmpty())
        assertTrue(songs.all { it.art?.startsWith("https://") == true })
        assertTrue(songs.first().art!!, songs.first().art!!.contains("=w544-h544"))
    }
}
