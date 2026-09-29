package com.kivan.motoparty.music

import android.util.Log
import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicLoad
import com.kivan.motoparty.core.MusicPause
import com.kivan.motoparty.core.MusicPlay
import com.kivan.motoparty.core.MusicState
import com.kivan.motoparty.core.MusicStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The host's music authority (PROTOCOL.md "Music flow"): queue, load -> ready -> play
 * handshake, pause/resume, and pausing around talk. Main thread only.
 */
class MusicController(
    private val scope: CoroutineScope,
    private val caches: TrackCaches,
    private val sync: SyncController,
    private val player: Player,
    private val hostNow: () -> Long,
    private val send: (Message) -> Unit,
    private val hasClient: () -> Boolean,
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
) {
    var queue: List<Track> = emptyList()
        private set
    var index = -1
        private set
    val current: Track? get() = queue.getOrNull(index)
    val upcoming: List<Track> get() = if (index < 0) queue else queue.drop(index + 1)
    val isPlaying: Boolean get() = sync.anchor?.playing == true

    private var startJob: Job? = null
    private val readyWaiters = HashMap<String, CompletableDeferred<Unit>>()
    private val clientReady = HashSet<String>()
    /** Tracks already re-sent after a "not decodable"; one retry each. */
    private val resent = HashSet<String>()

    /** True while talk (or a track that arrived during it) is holding a resume. */
    var pausedForTalk = false
        private set
    private var duckedForTalk = false
    private var talkPausing = false

    fun setQueue(tracks: List<Track>, start: Int = 0) {
        queue = QueueEdits.capped(tracks, start)
        index = start
        startCurrent(0)
    }

    /**
     * PROTOCOL.md "Browsing" `music.enqueue`: [EnqueueMode.NOW] replaces the queue; NEXT and END
     * insert after the current track or append, and act like NOW when nothing is loaded.
     */
    fun enqueue(mode: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (mode == EnqueueMode.NOW || current == null) return setQueue(tracks, 0)
        val nextBefore = upcoming.firstOrNull()
        queue = QueueEdits.inserted(queue, index, mode, tracks)
        onChanged()
        if (upcoming.firstOrNull() != nextBefore) prefetchNext()
    }

    /** `music.edit jump`: play `upcoming[i]` now. False when [id] no longer sits at [i] (stale). */
    fun jump(i: Int, id: String): Boolean {
        index = QueueEdits.at(queue, index, i, id) ?: return false
        startCurrent(0)
        return true
    }

    /** `music.edit remove`: drop `upcoming[i]`. False when [id] no longer sits at [i] (stale). */
    fun remove(i: Int, id: String): Boolean {
        val at = QueueEdits.at(queue, index, i, id) ?: return false
        queue = queue.toMutableList().apply { removeAt(at) }
        onChanged()
        if (i == 0) prefetchNext()
        return true
    }

    /** `music.edit clear`: drop every upcoming track; the current one plays on. */
    fun clearUpcoming() {
        if (upcoming.isEmpty()) return
        queue = queue.take(index + 1)
        onChanged()
    }

    fun next() {
        if (index + 1 >= queue.size) return stop()
        index++
        startCurrent(0)
    }

    fun previous() {
        val pos = sync.anchor?.expectedAt(hostNow()) ?: 0
        if (pos > RESTART_THRESHOLD_MS || index <= 0) {
            startCurrent(0)
        } else {
            index--
            startCurrent(0)
        }
    }

    fun pause() {
        // During talk both sides are already paused locally; a pause now only cancels the resume
        // talk was holding, so the music stays paused when talk closes.
        pausedForTalk = false
        val a = sync.anchor ?: return
        if (!a.playing) return
        val pos = a.expectedAt(hostNow()).coerceAtLeast(0)
        sync.apply(a.copy(positionMs = pos, atHostTimeMs = hostNow(), playing = false))
        send(MusicPause(a.id, pos))
        onChanged()
    }

    fun resume() {
        val a = sync.anchor ?: return
        if (a.playing) return
        playFrom(a.id, a.positionMs, START_LEAD_MS)
    }

    fun togglePlayPause() = if (isPlaying) pause() else resume()

    fun stop() {
        startJob?.cancel()
        sync.apply(null)
        player.stop()
        queue = emptyList()
        index = -1
        send(MusicStop)
        onChanged()
    }

    fun onTrackEnded() {
        if (current != null && isPlaying) next()
    }

    // ---- client ----

    fun onClientReady(id: String) {
        // A client that became ready after the 8 s timeout (or reconnected) joins mid-track.
        // Decided before completing the waiter: on Main.immediate that can run the start job
        // inline, which sends its own music.play.
        val a = sync.anchor
        val joinMidTrack = a != null && a.id == id && a.playing && startJob?.isActive != true
        clientReady += id
        readyWaiters.remove(id)?.complete(Unit)
        if (joinMidTrack && a != null) send(MusicPlay(a.id, a.positionMs, a.atHostTimeMs))
    }

    fun onClientError(id: String, message: String) {
        Log.w(TAG, "client could not load $id: $message")
        readyWaiters.remove(id)?.complete(Unit)
        if (!message.startsWith(NOT_DECODABLE)) return
        // The one format the client may not play is Opus in MP4 (AVPlayer, iOS 17+, unverified on
        // a real iPhone): from now on every track is AAC, for both phones. The host keeps playing
        // the Opus file it has open; the client gets the AAC one and joins mid-track when ready.
        // A next track already sent as Opus fails on its own and comes back through here; re-sending
        // it earlier would only join the client's in-flight download and fail with it.
        if (caches.opus) {
            caches.opus = false
            Log.w(TAG, "client cannot decode Opus-in-MP4; AAC for the rest of this session")
        }
        queue.firstOrNull { it.id == id }?.let { resend(it) }
    }

    fun onClientConnected() {
        clientReady.clear()
        resent.clear()
        val t = current ?: return
        if (caches.active.cached(t.id) != null) send(load(t))
        upcoming.firstOrNull()?.let { if (caches.active.cached(it.id) != null) send(load(it)) }
    }

    fun onClientGone() {
        clientReady.clear()
        readyWaiters.values.forEach { it.complete(Unit) }
        readyWaiters.clear()
    }

    // ---- talk ----

    fun onTalkOpen(duck: Boolean) {
        val a = sync.anchor
        if (duck) {
            player.volume = DUCK_VOLUME
            duckedForTalk = true
            return
        }
        talkPausing = true
        if (a != null && a.playing) {
            pausedForTalk = true
            val pos = a.expectedAt(hostNow()).coerceAtLeast(0)
            // Both sides pause locally on talk.open; no music.pause is sent.
            sync.apply(a.copy(positionMs = pos, atHostTimeMs = hostNow(), playing = false))
            onChanged()
        }
    }

    fun onTalkClose(resumeLeadMs: Long) {
        if (duckedForTalk) {
            player.volume = 1f
            duckedForTalk = false
        }
        talkPausing = false
        if (!pausedForTalk) return
        pausedForTalk = false
        val a = sync.anchor ?: return
        // Cold: the headset is on its way back from HFP to A2DP, so this start is late in a way
        // an ordinary one is not (SyncController.coldStartLatencyMs).
        playFrom(a.id, a.positionMs, resumeLeadMs, cold = true)
    }

    // ---- state ----

    fun musicState(): MusicState? {
        val t = current ?: return null
        val a = sync.anchor?.takeIf { it.id == t.id }
        return MusicState(
            id = t.id, title = t.title, artist = t.artist,
            playing = a?.playing ?: false,
            positionMs = a?.positionMs ?: 0,
            atHostTimeMs = a?.atHostTimeMs ?: hostNow(),
            durationMs = t.durationMs,
            art = t.art,
        )
    }

    // ---- internals ----

    private fun load(t: Track) = MusicLoad(t.id, t.path, t.title, t.artist, t.album, t.durationMs)

    private fun startCurrent(positionMs: Long) {
        val t = current ?: return
        startJob?.cancel()
        sync.apply(null)
        onChanged()
        startJob = scope.launch {
            try {
                val file = caches.active.ensure(t.id)
                player.load(t, file)
                if (hasClient() && t.id !in clientReady) {
                    val waiter = readyWaiters.getOrPut(t.id) { CompletableDeferred() }
                    send(load(t))
                    if (withTimeoutOrNull(READY_TIMEOUT_MS) { waiter.await() } == null) {
                        Log.w(TAG, "no music.ready for ${t.id} within 8 s; playing alone")
                    }
                    readyWaiters.remove(t.id)
                }
                playFrom(t.id, positionMs, START_LEAD_MS)
                prefetchNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not start ${t.id}", e)
                onError("Couldn't load ${t.title}")
            }
        }
    }

    private fun playFrom(id: String, positionMs: Long, leadMs: Long, cold: Boolean = false) {
        if (talkPausing) {
            // A track finished loading mid-talk: park it until talk closes.
            sync.apply(Anchor(id, positionMs, hostNow(), playing = false))
            pausedForTalk = true
            onChanged()
            return
        }
        val a = Anchor(id, positionMs, hostNow() + leadMs, playing = true)
        sync.apply(a, cold)
        send(MusicPlay(a.id, a.positionMs, a.atHostTimeMs))
        onChanged()
    }

    /** As soon as the current track starts, cache the next one and tell the client to fetch it. */
    private fun prefetchNext() {
        val next = upcoming.firstOrNull() ?: return
        scope.launch {
            runCatching { caches.active.ensure(next.id) }
                .onSuccess { if (hasClient()) send(load(next)) }
                .onFailure { Log.w(TAG, "prefetch ${next.id} failed", it) }
        }
    }

    /** Fetch [t] from the active cache and send its `music.load` again, once per track. */
    private fun resend(t: Track) {
        if (!resent.add(t.id)) return
        scope.launch {
            runCatching { caches.active.ensure(t.id) }
                .onSuccess { if (hasClient()) send(load(t)) }
                .onFailure { Log.w(TAG, "re-fetch ${t.id} failed", it) }
        }
    }

    companion object {
        const val START_LEAD_MS = 300L
        const val READY_TIMEOUT_MS = 8_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val DUCK_VOLUME = 0.2f
        /** PROTOCOL.md: a `music.error` message starting with this = the client cannot play the file. */
        const val NOT_DECODABLE = "not decodable"
        private const val TAG = "MusicController"
    }
}

/** The queue arithmetic of PROTOCOL.md "Browsing", pure so it can be tested without a player. */
internal object QueueEdits {
    /** Keeps `state.queue` well under the 64 KiB frame limit. */
    const val MAX_UPCOMING = 200

    /** At most [MAX_UPCOMING] tracks after [current]. */
    fun capped(queue: List<Track>, current: Int): List<Track> = queue.take(maxOf(current, -1) + 1 + MAX_UPCOMING)

    /** [tracks] put right after [current] ([EnqueueMode.NEXT]) or at the end (END), capped. */
    fun inserted(queue: List<Track>, current: Int, mode: String, tracks: List<Track>): List<Track> {
        val head = queue.take(current + 1)
        val upcoming = queue.drop(current + 1)
        return head + (if (mode == EnqueueMode.NEXT) tracks + upcoming else upcoming + tracks).take(MAX_UPCOMING)
    }

    /** The queue position of `upcoming[i]`, or null when that is not [id] any more. */
    fun at(queue: List<Track>, current: Int, i: Int, id: String): Int? {
        val at = current + 1 + i
        return at.takeIf { i >= 0 && queue.getOrNull(it)?.id == id }
    }
}
