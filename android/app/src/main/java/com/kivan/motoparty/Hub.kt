package com.kivan.motoparty

import com.kivan.motoparty.core.RepeatMode
import com.kivan.motoparty.core.SearchKind
import com.kivan.motoparty.lyrics.LyricsView
import com.kivan.motoparty.music.ArtistItem
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.DownloadProgress
import com.kivan.motoparty.music.MusicPhase
import com.kivan.motoparty.music.OutputRoute
import com.kivan.motoparty.music.Track
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the UI and overlay show. Written by [LinkService]; read-only for everyone else.
 *
 * Only what a screen draws and what changes when something happens: the numbers that move every
 * second are in [Diagnostics], the log in [Hub.logLines], and the playback position is the
 * [anchor] the screen interpolates itself. So an idle second rewrites nothing here and nothing
 * recomposes (UA4).
 */
@Immutable
data class LinkStatus(
    val running: Boolean = false,
    val nsdName: String? = null,
    val clientName: String? = null,
    val talkOpen: Boolean = false,
    /**
     * The open talk's microphone is live: the "live" earcon has fired. Between [talkOpen] and this
     * the headset is still switching (1–1.5 s on the AirPods route) and the button says so.
     */
    val talkLive: Boolean = false,
    /** A talk just closed and its audio teardown (the switch back to media) is still running. */
    val talkClosing: Boolean = false,
    /**
     * A spoken command can still be given in the open talk: the first-phrase window of a talk this
     * phone opened has not passed, or the talk is solo (every phrase is one). The Ride tab shows
     * the command list while it is set.
     */
    val commandWindow: Boolean = false,
    /** The last phrase the recogniser heard in the open talk, or null. Cleared when it closes. */
    val heard: String? = null,
    val nowPlaying: Track? = null,
    val playing: Boolean = false,
    /** Where the music is on the host clock; the screens interpolate from it ([PlaybackAnchor.at]). */
    val anchor: PlaybackAnchor? = null,
    /** Why [nowPlaying] is not playing yet (loading, waiting for the client, parked by talk), or null. */
    val musicPhase: MusicPhase? = null,
    val queue: List<Track> = emptyList(),
    /** `state.music.repeat`: the Ride tab's toggle. */
    val repeat: RepeatMode = RepeatMode.OFF,
    val busy: String? = null,
    /** The last spoken reply, for a few seconds ([LinkHost] clears it). */
    val lastAnnounce: String? = null,
    /** The last reply that was a failure ("No coverage"); stays until dismissed or the next success. */
    val error: String? = null,
    /** Where the music plays; the latency trim shown in Settings is this route's. Null until known. */
    val outputRoute: OutputRoute? = null,
    /** Ids of the tracks in the active cache: the "downloaded" mark on song rows. */
    val cached: Set<String> = emptySet(),
    /** Album and playlist downloads (Search tab), by collection id. */
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val search: SearchState = SearchState(),
    /**
     * The pages open on the Search tab over its results, the top one last (2026-10-02): an
     * artist, then one of their albums, and Back returns to the artist. Empty = the results.
     */
    val browse: List<BrowsePage> = emptyList(),
    /**
     * The "Use USB stereo mic (Lark) for talk" setting is on but no usable receiver is there, and
     * why (`no USB input`, `USB input … is mono`); null = nothing to warn about. The Ride tab shows
     * a warning while it is set.
     */
    val larkMissing: String? = null,
    /** The open talk fell back to the earbud mics because the Lark was missing at its open. */
    val talkOnEarbudsFallback: Boolean = false,
    /** A long Lark recording ([com.kivan.motoparty.audio.UsbStereoProbe.startLong]) is running. */
    val longRecording: Boolean = false,
    /**
     * The host runs without its microphone: the foreground service could not claim the
     * `microphone` type (a restart by the system in the background, or RECORD_AUDIO not granted),
     * so every talk is answered "unavailable" ([Hub.micFgsType]). Opening the app or pressing the
     * notification's Talk claims it again. Written by [LinkService].
     */
    val micOff: Boolean = false,
    /** The rider's cellular call, ringing or answered; null when there is none (2026-10-02). */
    val call: CallUi? = null,
    /**
     * [nowPlaying]'s lyrics as the host's own cache has them (2026-10-02); null with nothing
     * playing. Changes only when the track or its lookup does; the Ride screen shows them only
     * while the rider's lyrics toggle is on ([Settings.lyrics]).
     */
    val lyrics: LyricsView? = null,
)

/**
 * The Ride screen's call card ([com.kivan.motoparty.link.CallController]). Host-only: none of it
 * goes on the wire, the passenger never learns of the rider's call.
 */
@Immutable
data class CallUi(
    /** False: ringing (Answer / Decline); true: off-hook (End). */
    val onCall: Boolean,
    /** The contact, the number, or a plain label; null on an outgoing call. */
    val name: String?,
    /** `ANSWER_PHONE_CALLS` is granted: the buttons can act. Without it the phone's own UI must. */
    val canAnswer: Boolean,
    /** Can "answer" / "decline" be said into the Lark while it rings? */
    val voice: Voice,
) {
    enum class Voice {
        /** The Lark listen window is open. */
        LISTENING,
        /** No Lark receiver: buttons only. */
        NO_LARK,
        /** Not now (answered, failed capture, no recognizer): buttons only. */
        OFF,
    }
}

/**
 * The music's position as a fixed point: [positionMs] at [atMs] on `SystemClock.elapsedRealtime`,
 * moving only while [playing]. Published instead of a position that would change every second.
 */
@Immutable
data class PlaybackAnchor(val positionMs: Long, val atMs: Long, val playing: Boolean) {
    /** The position at [nowMs], never negative (a start scheduled ahead) nor past [durationMs] (when known). */
    fun at(nowMs: Long, durationMs: Long = 0): Long {
        val p = if (playing) positionMs + (nowMs - atMs) else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    /** [at] as a fraction of [durationMs], 0 when the length is unknown. */
    fun fraction(nowMs: Long, durationMs: Long): Float =
        if (durationMs > 0) (at(nowMs, durationMs).toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** The numbers of the Developer section; rewritten once a second, read by nothing else. */
@Immutable
data class Diagnostics(
    val clientAddress: String? = null,
    /** Client clock minus host clock from the last ping (t0 - t1; includes one-way delay). */
    val clientSkewMs: Long? = null,
    val lastPingAgeMs: Long? = null,
    val jitterTargetMs: Int = 0,
    val underruns: Int = 0,
    val udpIn: Long = 0,
    val udpOut: Long = 0,
    /** What our own call route picked, or null while no call route is held. */
    val audioDevice: String? = null,
    /** Every audio device the phone has, in short ([com.kivan.motoparty.audio.DeviceRoster]). */
    val audioDevices: String? = null,
    val lastDriftMs: Long? = null,
    val cacheMb: Long = 0,
)

@Immutable
/** The Search tab's last search: songs or collections, depending on [kind] ([SearchKind]). */
data class SearchState(
    val kind: String = SearchKind.SONGS,
    val query: String = "",
    val loading: Boolean = false,
    val songs: List<Track> = emptyList(),
    val collections: List<CollectionItem> = emptyList(),
    /** For [SearchKind.ARTISTS] (2026-10-02). */
    val artists: List<ArtistItem> = emptyList(),
    val error: String? = null,
)

/** A page open on the Search tab ([LinkStatus.browse]). */
sealed interface BrowsePage {
    val loading: Boolean
    val error: String?
}

/** An album or playlist page. */
@Immutable
data class BrowseState(
    val collection: CollectionItem,
    override val loading: Boolean = true,
    val tracks: List<Track> = emptyList(),
    override val error: String? = null,
) : BrowsePage

/**
 * An artist's page (2026-10-02): top songs, then albums and singles. From the Ride screen the
 * [artist] is only a name ([ArtistItem.id] empty) until the search for it answers.
 */
@Immutable
data class ArtistState(
    val artist: ArtistItem,
    override val loading: Boolean = true,
    val songs: List<Track> = emptyList(),
    val albums: List<CollectionItem> = emptyList(),
    override val error: String? = null,
) : BrowsePage

/** Requests from the UI to the running service. */
sealed interface UiAction {
    /** [kind] is a [SearchKind]. */
    data class Search(val kind: String, val query: String) : UiAction
    data class Browse(val collection: CollectionItem) : UiAction
    /** Open [artist]'s page over the current one (2026-10-02). */
    data class BrowseArtist(val artist: ArtistItem) : UiAction
    /**
     * The Ride screen's artist line was tapped: find the artist named [credit] and open their
     * page on the Search tab ([com.kivan.motoparty.music.Catalog.pickArtist]).
     */
    data class OpenArtist(val credit: String) : UiAction
    /** The Ride screen's album line was tapped: open the album that holds [track]. */
    data class OpenAlbum(val track: Track) : UiAction
    /** Back on the Search tab: closes the top page. */
    data object CloseBrowse : UiAction
    /** [mode] is an [com.kivan.motoparty.core.EnqueueMode]. */
    data class Enqueue(val mode: String, val tracks: List<Track>) : UiAction
    /** Jump to `upcoming[index]`, if it is still [id]. */
    data class Jump(val index: Int, val id: String) : UiAction
    data class Remove(val index: Int, val id: String) : UiAction
    /** Drag to reorder: `upcoming[index]`, if it is still [id], becomes `upcoming[to]`. */
    data class Move(val index: Int, val id: String, val to: Int) : UiAction
    /** The repeat toggle. */
    data class Repeat(val mode: RepeatMode) : UiAction
    data object ClearQueue : UiAction
    /** Undo of a [Remove]: [track] back at `upcoming[index]`. */
    data class Restore(val index: Int, val track: Track) : UiAction
    /** The rider closed the error banner ([LinkStatus.error]). */
    data object DismissError : UiAction
    /** Download every track of the album or playlist [collection] into the cache. */
    data class Download(val collection: CollectionItem, val tracks: List<Track>) : UiAction
    data class CancelDownload(val collectionId: String) : UiAction
    /** Typed text, handled exactly like a recognised utterance. */
    data class Command(val text: String) : UiAction
    data class Control(val action: String) : UiAction
    /** Debug: gate S4, the Lark receiver recorded as stereo ([com.kivan.motoparty.audio.UsbStereoProbe]). */
    data object UsbStereoProbe : UiAction
    /** Debug: start a long Lark recording, or stop the one running. */
    data object LongRecording : UiAction
    /** The call card's buttons (2026-10-02). */
    data object AnswerCall : UiAction
    data object DeclineCall : UiAction
    data object EndCall : UiAction
}

/** Process-wide state shared between the service, the overlay and the activity. */
object Hub {
    val status = MutableStateFlow(LinkStatus())
    /** Once a second while the host runs; only the Developer section collects it. */
    val diagnostics = MutableStateFlow(Diagnostics())
    /** The last [LOG_LINES] log lines, newest first; only the Developer section collects it. */
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val actions = MutableSharedFlow<UiAction>(extraBufferCapacity = 16)

    /**
     * Whether the foreground service actually holds the `microphone` type. It can be refused
     * (SecurityException on a sticky restart), and without it recording is impossible, which is
     * one of the "mic unavailable" cases of PROTOCOL.md "Talk flow" step 1. [LinkStatus.micOff]
     * is its negation while the host runs, for the notification and the Ride tab.
     */
    @Volatile
    var micFgsType: Boolean = false

    fun log(line: String) {
        android.util.Log.i("Motoparty", line)
        logLines.update { (listOf(line) + it).take(LOG_LINES) }
    }

    private const val LOG_LINES = 40
}
