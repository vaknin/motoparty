package com.kivan.motoparty

import com.kivan.motoparty.core.SearchKind
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.DownloadProgress
import com.kivan.motoparty.music.MusicPhase
import com.kivan.motoparty.music.OutputRoute
import com.kivan.motoparty.music.Track
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What the UI and overlay show. Written by [LinkService]; read-only for everyone else. */
data class LinkStatus(
    val running: Boolean = false,
    val nsdName: String? = null,
    val clientName: String? = null,
    val clientAddress: String? = null,
    /** Client clock minus host clock from the last ping (t0 - t1; includes one-way delay). */
    val clientSkewMs: Long? = null,
    val lastPingAgeMs: Long? = null,
    val talkOpen: Boolean = false,
    val jitterTargetMs: Int = 0,
    val underruns: Int = 0,
    val udpIn: Long = 0,
    val udpOut: Long = 0,
    /** What our own call route picked, or null while no call route is held. */
    val audioDevice: String? = null,
    /** Every audio device the phone has, in short ([com.kivan.motoparty.audio.DeviceRoster]). */
    val audioDevices: String? = null,
    val nowPlaying: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** Why [nowPlaying] is not playing yet (loading, waiting for the client, parked by talk), or null. */
    val musicPhase: MusicPhase? = null,
    val queue: List<Track> = emptyList(),
    val busy: String? = null,
    val lastAnnounce: String? = null,
    val lastDriftMs: Long? = null,
    /** Where the music plays; the latency trim shown in Settings is this route's. Null until known. */
    val outputRoute: OutputRoute? = null,
    val cacheMb: Long = 0,
    /** Ids of the tracks in the active cache: the "downloaded" mark on song rows. */
    val cached: Set<String> = emptySet(),
    /** Album and playlist downloads (Search tab), by collection id. */
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val search: SearchState = SearchState(),
    /** The album or playlist open on the Search tab, or null. */
    val browse: BrowseState? = null,
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
    val log: List<String> = emptyList(),
)

/** The Search tab's last search: songs or collections, depending on [kind] ([SearchKind]). */
data class SearchState(
    val kind: String = SearchKind.SONGS,
    val query: String = "",
    val loading: Boolean = false,
    val songs: List<Track> = emptyList(),
    val collections: List<CollectionItem> = emptyList(),
    val error: String? = null,
)

data class BrowseState(
    val collection: CollectionItem,
    val loading: Boolean = true,
    val tracks: List<Track> = emptyList(),
    val error: String? = null,
)

/** Requests from the UI to the running service. */
sealed interface UiAction {
    /** [kind] is a [SearchKind]. */
    data class Search(val kind: String, val query: String) : UiAction
    data class Browse(val collection: CollectionItem) : UiAction
    data object CloseBrowse : UiAction
    /** [mode] is an [com.kivan.motoparty.core.EnqueueMode]. */
    data class Enqueue(val mode: String, val tracks: List<Track>) : UiAction
    /** Jump to `upcoming[index]`, if it is still [id]. */
    data class Jump(val index: Int, val id: String) : UiAction
    data class Remove(val index: Int, val id: String) : UiAction
    data object ClearQueue : UiAction
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
}

/** Process-wide state shared between the service, the overlay and the activity. */
object Hub {
    val status = MutableStateFlow(LinkStatus())
    val actions = MutableSharedFlow<UiAction>(extraBufferCapacity = 16)

    /**
     * Whether the foreground service actually holds the `microphone` type. It can be refused
     * (SecurityException on a sticky restart), and without it recording is impossible, which is
     * one of the "mic unavailable" cases of PROTOCOL.md "Talk flow" step 1.
     */
    @Volatile
    var micFgsType: Boolean = false

    fun log(line: String) {
        android.util.Log.i("Motoparty", line)
        status.update { it.copy(log = (listOf(line) + it.log).take(40)) }
    }
}
