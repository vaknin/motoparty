package com.kivan.motoparty.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import com.kivan.motoparty.BrowseState
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.SearchState
import com.kivan.motoparty.Settings
import com.kivan.motoparty.core.SearchKind
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.DownloadProgress
import com.kivan.motoparty.music.History
import com.kivan.motoparty.music.RecentSearch
import com.kivan.motoparty.music.Track
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws every tab with made-up state, so the screens can be looked at without a phone. PNGs go
 * to app/build/outputs/roborazzi/ with `./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreensTest*'`;
 * without -Pscreenshots this only checks that each screen composes. Cover art is a flat colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val colors = listOf(0xFFB45309, 0xFF0E7490, 0xFF7C3AED, 0xFFBE123C, 0xFF15803D, 0xFF1D4ED8)

    private fun track(i: Int, title: String, artist: String, album: String? = null) =
        Track("id$i", title, artist, album, durationMs = 150_000L + i * 17_000L, art = "art$i")

    private val songs = listOf(
        track(0, "Money", "Pink Floyd", "The Dark Side of the Moon"),
        track(1, "Time", "Pink Floyd", "The Dark Side of the Moon"),
        track(2, "Wish You Were Here", "Pink Floyd"),
        track(3, "Comfortably Numb", "Pink Floyd"),
        track(4, "Shine On You Crazy Diamond (Pts. 1-5)", "Pink Floyd"),
        track(5, "Another Brick in the Wall, Pt. 2", "Pink Floyd"),
    )

    private val playing = LinkStatus(
        running = true,
        nsdName = "Pixel 8",
        clientName = "iPhone",
        nowPlaying = songs[0],
        playing = true,
        positionMs = 97_000,
        queue = songs.drop(1),
        lastAnnounce = "Playing album The Dark Side of the Moon by Pink Floyd",
    )

    @OptIn(DelicateCoilApi::class)
    @Before
    fun fakeArt() {
        val engine = FakeImageLoaderEngine.Builder().apply {
            for (i in 0 until 10) intercept("art$i", ColorImage(colors[i % colors.size].toInt()))
            default(ColorImage(Color.DarkGray.toArgb()))
        }.build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext()).components { add(engine) }.build(),
        )
    }

    private fun shoot(
        name: String,
        status: LinkStatus,
        tab: Tab,
        permissions: List<Permission> = emptyList(),
        history: History = History(),
    ) {
        compose.setContent { MotopartyTheme { Motoparty(status, Settings(), permissions, Callbacks(), tab, history) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun ridePlaying() = shoot("1-ride-playing", playing, Tab.RIDE)

    @Test
    fun rideWaitingEmpty() = shoot(
        "2-ride-waiting", LinkStatus(running = true, nsdName = "Pixel 8"), Tab.RIDE,
        listOf(Permission("Microphone", true, "m"), Permission("Draw over other apps", false, "o")),
    )

    /** A solo talk: the one button reads END TALK, and the commands card says every phrase is one. */
    @Test
    fun rideTalkingSolo() = shoot("2b-ride-talking-solo", playing.copy(clientName = null, talkOpen = true, playing = false), Tab.RIDE)

    /** The Lark setting is on and no receiver is enumerated: the amber warning under the status. */
    @Test
    fun rideLarkMissing() = shoot("2c-ride-lark-missing", playing.copy(larkMissing = "no USB input"), Tab.RIDE)

    /** …and a talk that opened anyway runs on the earbud mics: the warning turns red. */
    @Test
    fun rideLarkMissingInTalk() = shoot(
        "2d-ride-lark-missing-talk",
        playing.copy(larkMissing = "no USB input", talkOpen = true, talkOnEarbudsFallback = true, playing = false),
        Tab.RIDE,
    )

    @Test
    fun searchSongs() = shoot(
        "3-search-songs",
        playing.copy(search = SearchState(SearchKind.SONGS, "pink floyd", songs = songs)),
        Tab.SEARCH,
    )

    @Test
    fun searchAlbums() = shoot(
        "4-search-albums",
        playing.copy(
            search = SearchState(
                SearchKind.ALBUMS, "pink floyd",
                collections = listOf(
                    CollectionItem("a1", "The Dark Side of the Moon", "Pink Floyd", 10, "art0"),
                    CollectionItem("a2", "Wish You Were Here", "Pink Floyd", 5, "art2"),
                    CollectionItem("a3", "The Wall", "Pink Floyd", 26, "art5"),
                    CollectionItem("a4", "Animals", "Pink Floyd", 5, null),
                ),
            ),
        ),
        Tab.SEARCH,
    )

    /** An empty search box: recent searches and recently played, some of them downloaded. */
    @Test
    fun searchHistory() = shoot(
        "4b-search-history",
        playing.copy(cached = setOf("id2", "id3")),
        Tab.SEARCH,
        history = History(
            searches = listOf(
                RecentSearch(SearchKind.ALBUMS, "dark side of the moon"),
                RecentSearch(SearchKind.SONGS, "comfortably numb"),
                RecentSearch(SearchKind.PLAYLISTS, "road trip"),
            ),
            played = songs.drop(1).take(4),
        ),
    )

    /** An album downloading for the ride: progress on the button, marks on the songs already in. */
    @Test
    fun albumDownloading() = shoot(
        "5b-album-downloading",
        playing.copy(
            cached = setOf("id0", "id1"),
            downloads = mapOf("a1" to DownloadProgress(2, 5)),
            browse = BrowseState(
                CollectionItem("a1", "The Dark Side of the Moon", "Pink Floyd", 5, "art0"),
                loading = false,
                tracks = songs.take(5),
            ),
        ),
        Tab.SEARCH,
    )

    @Test
    fun album() = shoot(
        "5-album",
        playing.copy(
            browse = BrowseState(
                CollectionItem("a1", "The Dark Side of the Moon", "Pink Floyd", 10, "art0"),
                loading = false,
                tracks = songs.take(2) + listOf(
                    track(0, "Us and Them", "Pink Floyd"),
                    track(0, "Brain Damage", "Pink Floyd"),
                    track(0, "Eclipse", "Pink Floyd"),
                ),
            ),
        ),
        Tab.SEARCH,
    )

    @Test
    fun queue() = shoot("6-queue", playing, Tab.QUEUE)

    @Test
    fun queueEmpty() = shoot("7-queue-empty", LinkStatus(running = true), Tab.QUEUE)

    @Test
    fun settings() = shoot("8-settings", playing, Tab.SETTINGS)
}
