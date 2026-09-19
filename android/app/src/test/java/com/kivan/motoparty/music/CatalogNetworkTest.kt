package com.kivan.motoparty.music

import com.kivan.motoparty.core.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** Live YouTube tests; skipped unless `./gradlew test -Pnetwork`. */
class CatalogNetworkTest {
    private val http = OkHttpClient()

    @Before fun onlyWithNetwork() = assumeTrue(System.getProperty("motoparty.network") == "true")

    @Test
    fun searchResolveAndDownloadASong(): Unit = runBlocking {
        val catalog = Catalog(http)
        val result = catalog.search(Command.Kind.SONG, "bohemian rhapsody queen")
        println("song -> ${result.label}: ${result.tracks}")
        val track = result.tracks.first()
        val audio = catalog.resolveAudio(track.id)
        println("resolved itag ${audio.itag}, ${audio.url.take(80)}")
        val dir = Files.createTempDirectory("tracks").toFile()
        val cache = TrackCache(dir, http, { catalog.resolveAudio(it).url }, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val start = System.nanoTime()
        val file = cache.ensure(track.id)
        val head = file.readBytes().copyOfRange(4, 8).toString(Charsets.US_ASCII)
        println("downloaded ${file.length()} bytes in ${(System.nanoTime() - start) / 1_000_000} ms, box '$head'")
        assertTrue(file.length() > 500_000)
        assertTrue(head == "ftyp")
        dir.deleteRecursively()
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
}
