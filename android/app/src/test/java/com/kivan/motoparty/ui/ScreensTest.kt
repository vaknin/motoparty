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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kivan.motoparty.ArtistState
import com.kivan.motoparty.BrowseState
import com.kivan.motoparty.CallUi
import com.kivan.motoparty.Diagnostics
import com.kivan.motoparty.PlaybackAnchor
import com.kivan.motoparty.music.MusicPhase
import com.kivan.motoparty.music.OutputRoute
import kotlinx.coroutines.flow.MutableStateFlow
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.SearchState
import com.kivan.motoparty.Settings
import com.kivan.motoparty.core.RepeatMode
import com.kivan.motoparty.core.SearchKind
import com.kivan.motoparty.music.ArtistItem
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.DownloadProgress
import com.kivan.motoparty.music.History
import com.kivan.motoparty.music.RecentSearch
import com.kivan.motoparty.music.Track
import com.kivan.motoparty.audio.MicLevel
import com.kivan.motoparty.lyrics.Lrc
import com.kivan.motoparty.lyrics.LyricsView
import org.junit.After
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
        // Not a moving anchor: the position on the PNG is the same on every run.
        anchor = PlaybackAnchor(97_000, 0, playing = false),
        queue = songs.drop(1),
    )

    private val dev = DevFlows(
        MutableStateFlow(Diagnostics(clientAddress = "192.168.43.17", clientSkewMs = 12, lastPingAgeMs = 340, jitterTargetMs = 60, cacheMb = 412)),
        MutableStateFlow(listOf("host up as \"Pixel 8\"", "client \"iPhone\" connected from 192.168.43.17")),
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

    /** Nothing a test put into the two process-wide values leaks into the next one. */
    @After
    fun reset() {
        ArtTints.clear()
        MicLevel.peak = 0
    }

    /**
     * The glow's colour for [art], as the palette would give it for a cover of [cover]: Robolectric
     * loads no art, and the fake loader's flat colours have no pixels to read.
     */
    private fun tint(art: String, cover: Long) = ArtTints.put(art, ambientTint(listOf(cover.toInt()), 0xFF0E1013.toInt()))

    private val longTitle = track(0, "Shine On You Crazy Diamond (Parts I–V) [2011 Remastered Version] – Live at Knebworth", "Pink Floyd")

    private fun shoot(
        name: String,
        status: LinkStatus,
        tab: Tab,
        permissions: List<Permission> = emptyList(),
        history: History = History(),
        settings: Settings = Settings(),
        prefs: UiPrefs = UiPrefs(),
    ) {
        compose.setContent { MotopartyTheme { Motoparty(status, settings, permissions, Callbacks(), tab, history, prefs, dev = dev) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun ridePlaying() = shoot("1-ride-playing", playing, Tab.RIDE)

    // ---- lyrics (2026-10-02): made-up words, the third line half sung at 97.8 s ----

    private val lyricLines = Lrc.parse(
        """
        [01:26.00]Paper boats along the gutter run
        [01:30.00]Counting coins beneath a paper moon
        [01:34.00]Every promise folded into June
        [01:37.00]Spend the night and whistle out of tune
        [01:41.00]
        [01:45.00]Wake me when the morning comes too soon
        """.trimIndent(),
    )
    private val withLyrics = playing.copy(
        anchor = PlaybackAnchor(97_800, 0, playing = false),
        lyrics = LyricsView("id0", LyricsView.Kind.FOUND, lyricLines),
    )
    private val lyricsOn = Settings(lyrics = true)

    @Test
    fun rideLyrics() = shoot("1w-ride-lyrics", withLyrics, Tab.RIDE, settings = lyricsOn, prefs = UiPrefs().withLyricsOffset("id0", -400))

    @Test
    @Config(qualifiers = "w412dp-h840dp-420dpi")
    fun rideLyricsPixelHeight() = shoot("1w2-ride-lyrics-pixel-height", withLyrics, Tab.RIDE, settings = lyricsOn)

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideLyricsLandscape() = shoot("1x-ride-lyrics-landscape", withLyrics, Tab.RIDE, settings = lyricsOn)

    /** Short upright: two banners leave the middle under 400 dp, so the compact lyrics. */
    @Test
    @Config(qualifiers = "w412dp-h700dp-420dpi")
    fun rideLyricsShort() = shoot(
        "1x2-ride-lyrics-short",
        withLyrics.copy(micOff = true, error = "Couldn't find pink floid"),
        Tab.RIDE,
        settings = lyricsOn,
    )

    /** Toggle off (the default): the cover, whatever lyrics the host has. */
    @Test
    fun rideLyricsOff() = shoot("1y-ride-lyrics-off", withLyrics, Tab.RIDE)

    @Test
    fun rideLyricsNotFound() = shoot(
        "1z-ride-lyrics-not-found",
        playing.copy(lyrics = LyricsView("id0", LyricsView.Kind.NOT_FOUND)),
        Tab.RIDE,
        settings = lyricsOn,
    )

    @Test
    fun rideLyricsLoading() = shoot(
        "1z2-ride-lyrics-loading",
        playing.copy(lyrics = LyricsView("id0", LyricsView.Kind.LOADING)),
        Tab.RIDE,
        settings = lyricsOn,
    )

    /** At an instrumental break: the "♪" in the middle. */
    @Test
    fun rideLyricsBreak() = shoot(
        "1z3-ride-lyrics-break",
        withLyrics.copy(anchor = PlaybackAnchor(102_000, 0, playing = false)),
        Tab.RIDE,
        settings = lyricsOn,
    )

    /** The cover's colour behind the music: an orange cover here, the strongest case being a light one. */
    @Test
    fun rideTinted() {
        tint("art0", colors[0])
        shoot("1m-ride-tinted", playing, Tab.RIDE)
    }

    @Test
    fun rideTintedBlue() {
        tint("art0", colors[5])
        shoot("1n-ride-tinted-blue", playing, Tab.RIDE)
    }

    /** A yellow cover is the brightest tint there is; the mic-off banner's red has to stay readable on it. */
    @Test
    fun rideTintedYellowWithWarnings() {
        tint("art0", 0xFFFFE600)
        shoot(
            "1o-ride-tinted-yellow-warnings",
            playing.copy(micOff = true, playing = false, musicPhase = MusicPhase.LOADING, error = "Couldn't find pink floid"),
            Tab.RIDE,
        )
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideTintedLandscape() {
        tint("art0", colors[0])
        shoot("1p-ride-tinted-landscape", playing, Tab.RIDE)
    }

    /** A title far too long for the line: one line, from its start (it scrolls on a phone). */
    @Test
    fun rideLongTitle() = shoot("1q-ride-long-title", playing.copy(nowPlaying = longTitle), Tab.RIDE)

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideLongTitleLandscape() = shoot("1r-ride-long-title-landscape", playing.copy(nowPlaying = longTitle), Tab.RIDE)

    @Test
    fun miniPlayerLongTitle() = shoot("1s-mini-player-long-title", playing.copy(nowPlaying = longTitle), Tab.QUEUE)

    /** A live talk with the rider speaking (-14 dB): the meter in the LIVE pill is about four fifths full. */
    @Test
    fun rideTalkLiveMeter() {
        MicLevel.peak = 6500
        tint("art0", colors[0])
        shoot(
            "1t-ride-talk-live-meter",
            playing.copy(talkOpen = true, talkLive = true, playing = false, musicPhase = MusicPhase.PAUSED_FOR_TALK, heard = "are you cold"),
            Tab.RIDE,
        )
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideTalkLiveMeterLandscape() {
        MicLevel.peak = 1000
        shoot(
            "1u-ride-talk-live-meter-landscape",
            playing.copy(talkOpen = true, talkLive = true, playing = false, musicPhase = MusicPhase.PAUSED_FOR_TALK, heard = "are you cold"),
            Tab.RIDE,
        )
    }

    /** The height the Ride tab really gets on the Pixel 8, under the status bar and above the gesture bar. */
    @Test
    @Config(qualifiers = "w412dp-h840dp-420dpi")
    fun ridePlayingRealHeight() = shoot("1a-ride-playing-pixel-height", playing.copy(lastAnnounce = "Playing Money by Pink Floyd"), Tab.RIDE)

    /** The handlebar mount: tabs on a rail, the music on the left, TALK the whole right side. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun ridePlayingLandscape() = shoot("1b-ride-playing-landscape", playing, Tab.RIDE)

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideTalkLiveLandscape() = shoot(
        "1c-ride-talk-live-landscape",
        playing.copy(talkOpen = true, talkLive = true, playing = false, musicPhase = MusicPhase.PAUSED_FOR_TALK, heard = "are you cold"),
        Tab.RIDE,
    )

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideTalkOpeningLandscape() = shoot(
        "1d-ride-talk-opening-landscape",
        playing.copy(talkOpen = true, commandWindow = true, playing = false),
        Tab.RIDE,
    )

    /** The host is off: one button, where TALK would be. */
    @Test
    fun rideHostOff() = shoot("1e-ride-host-off", LinkStatus(), Tab.RIDE)

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideHostOffLandscape() = shoot("1f-ride-host-off-landscape", LinkStatus(), Tab.RIDE)

    /** Pressed, the headset is still switching: the ring, "Connecting…", and the commands in place of the music. */
    @Test
    fun rideTalkOpening() = shoot(
        "1g-ride-talk-opening",
        playing.copy(talkOpen = true, commandWindow = true, playing = false, musicPhase = MusicPhase.PAUSED_FOR_TALK),
        Tab.RIDE,
    )

    /** Live, and the first phrase was a conversation: red END TALK with the LIVE pill, and what was heard. */
    @Test
    fun rideTalkLive() = shoot(
        "1h-ride-talk-live",
        playing.copy(talkOpen = true, talkLive = true, playing = false, musicPhase = MusicPhase.PAUSED_FOR_TALK, heard = "are you cold"),
        Tab.RIDE,
    )

    @Test
    fun rideTalkClosing() = shoot("1i-ride-talk-closing", playing.copy(talkClosing = true, playing = false), Tab.RIDE)

    /** The service lost its microphone: the red banner is the button that brings it back. */
    @Test
    fun rideMicOff() = shoot("1j-ride-mic-off", playing.copy(micOff = true), Tab.RIDE)

    /** A failed command stays as a banner with a ✕; the song is still downloading. */
    @Test
    fun rideErrorAndLoading() = shoot(
        "1k-ride-error-loading",
        playing.copy(error = "Couldn't find pink floid", playing = false, musicPhase = MusicPhase.LOADING, anchor = null),
        Tab.RIDE,
    )

    /** What the "Say …" line opens. */
    @Test
    fun commandsSheetContent() {
        compose.setContent {
            MotopartyTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) { CommandsList(solo = false, Modifier.padding(16.dp)) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1l-commands-sheet.png")
    }

    @Test
    fun rideWaitingEmpty() = shoot(
        "2-ride-waiting", LinkStatus(running = true, nsdName = "Pixel 8"), Tab.RIDE,
        listOf(Permission("Microphone", true, "m"), Permission("Draw over other apps", false, "o")),
    )

    /** A solo talk: the one button reads END TALK, and the commands card says every phrase is one. */
    @Test
    fun rideTalkingSolo() = shoot(
        "2b-ride-talking-solo",
        playing.copy(clientName = null, talkOpen = true, talkLive = true, commandWindow = true, playing = false),
        Tab.RIDE,
    )

    /** The Lark setting is on and no receiver is enumerated: the amber warning under the status. */
    @Test
    fun rideLarkMissing() = shoot("2c-ride-lark-missing", playing.copy(larkMissing = "no USB input"), Tab.RIDE)

    /** …and a talk that opened anyway runs on the earbud mics: the warning turns red. */
    @Test
    fun rideLarkMissingInTalk() = shoot(
        "2d-ride-lark-missing-talk",
        playing.copy(larkMissing = "no USB input", talkOpen = true, talkLive = true, talkOnEarbudsFallback = true, playing = false),
        Tab.RIDE,
    )

    // ---- the rider's cellular call (2026-10-02) ----

    private val ringing = playing.copy(
        musicPhase = MusicPhase.ON_CALL,
        call = CallUi(onCall = false, name = "Dana Levi", canAnswer = true, voice = CallUi.Voice.LISTENING),
    )

    /** It rings: the name, "say answer", and Decline / Answer for a gloved thumb. */
    @Test
    fun rideCallRinging() = shoot("2e-ride-call-ringing", ringing, Tab.RIDE)

    /** The handlebar mount: the card is shorter, the buttons are not. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideCallRingingLandscape() = shoot("2f-ride-call-ringing-landscape", ringing, Tab.RIDE)

    /** No Lark: the buttons only, and the card says why "answer" is not heard. A Hebrew name. */
    @Test
    fun rideCallRingingNoLark() = shoot(
        "2g-ride-call-no-lark",
        ringing.copy(call = CallUi(onCall = false, name = "אמא", canAnswer = true, voice = CallUi.Voice.NO_LARK)),
        Tab.RIDE,
    )

    /** Answered: "On a call" and one big End. */
    @Test
    fun rideOnCall() = shoot(
        "2h-ride-on-call",
        ringing.copy(call = CallUi(onCall = true, name = "Dana Levi", canAnswer = true, voice = CallUi.Voice.OFF)),
        Tab.RIDE,
    )

    /** "Answer calls" not granted: no buttons that would do nothing. */
    @Test
    fun rideCallNoAnswerPermission() = shoot(
        "2i-ride-call-no-permission",
        ringing.copy(call = CallUi(onCall = false, name = "+972 50-123-4567", canAnswer = false, voice = CallUi.Voice.LISTENING)),
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
            browse = listOf(
                BrowseState(
                    CollectionItem("a1", "The Dark Side of the Moon", "Pink Floyd", 5, "art0"),
                    loading = false,
                    tracks = songs.take(5),
                ),
            ),
        ),
        Tab.SEARCH,
    )

    @Test
    fun album() = shoot(
        "5-album",
        playing.copy(
            browse = listOf(
                BrowseState(
                    CollectionItem("a1", "The Dark Side of the Moon", "Pink Floyd", 10, "art0"),
                    loading = false,
                    tracks = songs.take(2) + listOf(
                        track(0, "Us and Them", "Pink Floyd"),
                        track(0, "Brain Damage", "Pink Floyd"),
                        track(0, "Eclipse", "Pink Floyd"),
                    ),
                ),
            ),
        ),
        Tab.SEARCH,
    )

    private val floyd = ArtistItem("UCpf", "Pink Floyd", "art1")
    private val floydAlbums = listOf(
        CollectionItem("a1", "The Dark Side of the Moon", "Pink Floyd", 10, "art0"),
        CollectionItem("a2", "Wish You Were Here", "Pink Floyd", 5, "art2"),
        CollectionItem("a3", "The Wall", "Pink Floyd", 26, "art5"),
        CollectionItem("a4", "Hey Hey Rise Up", "Pink Floyd", 2, null),
    )
    private val floydPage = ArtistState(floyd, loading = false, songs = songs.take(4), albums = floydAlbums)

    /** Artist results (2026-10-02): round pictures, the Artists chip on. */
    @Test
    fun searchArtists() = shoot(
        "4c-search-artists",
        playing.copy(
            search = SearchState(
                SearchKind.ARTISTS, "pink floyd",
                artists = listOf(floyd, ArtistItem("UCdg", "David Gilmour", "art3"), ArtistItem("UCrw", "Roger Waters", null)),
            ),
        ),
        Tab.SEARCH,
    )

    /** An artist's page: picture, Play top songs, the songs, then albums and singles. */
    @Test
    fun artist() = shoot("5c-artist", playing.copy(browse = listOf(floydPage)), Tab.SEARCH)

    /** An album opened from the artist's page: Back says where it goes. */
    @Test
    fun albumFromArtist() = shoot(
        "5d-album-from-artist",
        playing.copy(
            browse = listOf(
                floydPage,
                BrowseState(floydAlbums[0], loading = false, tracks = songs.take(2)),
            ),
        ),
        Tab.SEARCH,
    )

    /** The Ride screen's artist tap, while the host looks the name up; then nothing found. */
    @Test
    fun artistFromRide() = shoot(
        "5e-artist-from-ride",
        playing.copy(browse = listOf(ArtistState(ArtistItem("", "Pink Floyd & Friends")))),
        Tab.SEARCH,
    )

    @Test
    fun artistNotFound() = shoot(
        "5f-artist-not-found",
        playing.copy(browse = listOf(ArtistState(ArtistItem("", "Nobody"), loading = false, error = "No artist found for “Nobody”"))),
        Tab.SEARCH,
    )

    @Test
    fun queue() = shoot("6-queue", playing, Tab.QUEUE)

    /** The repeat toggle lit (track: the "1" icon), in both layouts. */
    @Test
    fun rideRepeat() = shoot("1t-ride-repeat-track", playing.copy(repeat = RepeatMode.TRACK), Tab.RIDE)

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun rideRepeatLandscape() = shoot("1v-ride-repeat-queue-landscape", playing.copy(repeat = RepeatMode.QUEUE), Tab.RIDE)

    @Test
    fun queueEmpty() = shoot("7-queue-empty", LinkStatus(running = true), Tab.QUEUE)

    /** Only a current song: the row has the play/pause button and nothing comes after. */
    @Test
    fun queueOnlyCurrent() = shoot("7b-queue-only-current", playing.copy(queue = emptyList(), playing = false), Tab.QUEUE)

    @Test
    fun settings() = shoot("8-settings", playing.copy(outputRoute = OutputRoute("bt:aa", "AirPods Pro", bluetooth = true)), Tab.SETTINGS)

    @Test
    @Config(qualifiers = "w412dp-h1900dp-420dpi")
    fun settingsWhole() = shoot("8b-settings-whole", playing.copy(outputRoute = OutputRoute("bt:aa", "AirPods Pro", bluetooth = true)), Tab.SETTINGS)
}
