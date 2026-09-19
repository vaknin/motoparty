package com.kivan.motoparty.music

import android.util.Log
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
    private val cache: TrackCache,
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
    private var pausedForTalk = false
    private var duckedForTalk = false
    private var talkPausing = false

    fun setQueue(tracks: List<Track>, start: Int = 0) {
        queue = tracks
        index = start
        startCurrent(0)
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
    }

    fun onClientConnected() {
        clientReady.clear()
        val t = current ?: return
        if (cache.cached(t.id) != null) send(load(t))
        upcoming.firstOrNull()?.let { if (cache.cached(it.id) != null) send(load(it)) }
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
        playFrom(a.id, a.positionMs, resumeLeadMs)
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
                val file = cache.ensure(t.id)
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

    private fun playFrom(id: String, positionMs: Long, leadMs: Long) {
        if (talkPausing) {
            // A track finished loading mid-talk: park it until talk closes.
            sync.apply(Anchor(id, positionMs, hostNow(), playing = false))
            pausedForTalk = true
            onChanged()
            return
        }
        val a = Anchor(id, positionMs, hostNow() + leadMs, playing = true)
        sync.apply(a)
        send(MusicPlay(a.id, a.positionMs, a.atHostTimeMs))
        onChanged()
    }

    /** As soon as the current track starts, cache the next one and tell the client to fetch it. */
    private fun prefetchNext() {
        val next = upcoming.firstOrNull() ?: return
        scope.launch {
            runCatching { cache.ensure(next.id) }
                .onSuccess { if (hasClient()) send(load(next)) }
                .onFailure { Log.w(TAG, "prefetch ${next.id} failed", it) }
        }
    }

    companion object {
        const val START_LEAD_MS = 300L
        const val READY_TIMEOUT_MS = 8_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val DUCK_VOLUME = 0.2f
        private const val TAG = "MusicController"
    }
}
