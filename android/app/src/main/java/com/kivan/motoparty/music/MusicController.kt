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
import kotlin.random.Random

/**
 * The host's music authority (PROTOCOL.md "Music flow"): queue, load -> ready -> play
 * handshake, pause/resume, and pausing around talk. Main thread only.
 */
class MusicController(
    private val scope: CoroutineScope,
    private val caches: TrackStore,
    private val sync: SyncController,
    private val player: LocalPlayer,
    private val hostNow: () -> Long,
    private val send: (Message) -> Unit,
    private val hasClient: () -> Boolean,
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
    /** The lines a bench run reads (`pause at …`, ignored `music.error`s); logcat by default. */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** A track really started playing (not parked, not a resume of the same one): for history. */
    private val onStarted: (Track) -> Unit = {},
) {
    var queue: List<Track> = emptyList()
        private set
    var index = -1
        private set
    val current: Track? get() = queue.getOrNull(index)
    val upcoming: List<Track> get() = if (index < 0) queue else queue.drop(index + 1)
    val isPlaying: Boolean get() = sync.anchor?.playing == true

    private var startJob: Job? = null
    /** Where [startJob] is: [MusicPhase.LOADING] or [MusicPhase.WAITING_CLIENT]; null once it plays. */
    private var starting: MusicPhase? = null
    /** A `music.load` sent for the id, awaiting its `music.ready` (or `music.error`). */
    private val readyWaiters = HashMap<String, CompletableDeferred<Unit>>()
    /**
     * The current track once its file is cached, i.e. once `GET /track/<id>.m4a` can serve it;
     * until then `state` does not name it (see [musicState]).
     */
    private var servableId: String? = null
    private val clientReady = HashSet<String>()
    /** Tracks already re-sent after a "not decodable"; one retry each. */
    private val resent = HashSet<String>()

    /** True while talk (or a track that arrived during it) is holding a resume. */
    var pausedForTalk = false
        private set
    private var duckedForTalk = false
    private var talkPausing = false
    /**
     * A `pause` was said in this talk: nothing resumes after it, not even a track that `next` or
     * `previous` picked later in the same talk (PROTOCOL.md "Commands", *Effect on the talk*).
     */
    private var resumeCancelled = false
    /** No `music.play` is anchored before this host time (a spoken `play` that ended a talk). */
    private var startNotBeforeMs = 0L
    /** The track [onStarted] was last called for since its [startCurrent]; a resume is not a start. */
    private var startedId: String? = null
    /** The next track whose `music.load` [prefetchNext] sent, so a re-run does not repeat it. */
    private var sentNextId: String? = null
    /** The running [prefetchNext]; a newer queue replaces it. */
    private var prefetchJob: Job? = null

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
        val aheadBefore = upcoming.take(PREFETCH_AHEAD)
        queue = QueueEdits.inserted(queue, index, mode, tracks)
        onChanged()
        if (upcoming.take(PREFETCH_AHEAD) != aheadBefore) prefetchNext()
    }

    /** `music.edit jump`: play `upcoming[i]` now. False when [id] no longer sits at [i] (stale). */
    fun jump(i: Int, id: String): Boolean {
        index = QueueEdits.at(queue, index, i, id) ?: return false
        startCurrent(0)
        return true
    }

    /** Would [jump] accept `upcoming[i]` = [id]? A stale jump must not end a talk (Browsing step 3). */
    fun canJump(i: Int, id: String): Boolean = QueueEdits.at(queue, index, i, id) != null

    /**
     * Spoken `shuffle` (PROTOCOL.md "Commands"): shuffle the upcoming tracks, the current one plays
     * on. False, and nothing changes, with fewer than two upcoming.
     */
    fun shuffleUpcoming(random: Random = Random.Default): Boolean {
        if (upcoming.size < 2) return false
        queue = QueueEdits.shuffled(queue, index, random)
        onChanged()
        prefetchNext()
        return true
    }

    /** `music.edit remove`: drop `upcoming[i]`. False when [id] no longer sits at [i] (stale). */
    fun remove(i: Int, id: String): Boolean {
        val at = QueueEdits.at(queue, index, i, id) ?: return false
        queue = queue.toMutableList().apply { removeAt(at) }
        onChanged()
        if (i < PREFETCH_AHEAD) prefetchNext()
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
        if (talkPausing) resumeCancelled = true
        val a = sync.anchor ?: return
        if (!a.playing) return
        val pos = a.expectedAt(hostNow()).coerceAtLeast(0)
        log("pause at $pos ms")
        sync.apply(a.copy(positionMs = pos, atHostTimeMs = hostNow(), playing = false))
        send(MusicPause(a.id, pos))
        onChanged()
    }

    fun resume() {
        resumeCancelled = false
        val a = sync.anchor ?: return
        if (a.playing) return
        // playFrom parks it while talk holds the music; say so, or the log reads like a dead play.
        log(if (talkPausing) "resume parked: talk open (at ${a.positionMs} ms)" else "resume from ${a.positionMs} ms")
        playFrom(a.id, a.positionMs, START_LEAD_MS)
    }

    fun togglePlayPause() = if (isPlaying) pause() else resume()

    /** Is there a track [resume] could start? */
    val canResume: Boolean get() = sync.anchor != null

    /**
     * A spoken `play` is ending the talk: the music that talk paused must not come back for the
     * second or two before the new track does. Returns whether there was a resume to drop, so the
     * host can [resume] after all if the search fails.
     */
    fun dropResumeAfterTalk(): Boolean {
        val had = pausedForTalk
        pausedForTalk = false
        return had
    }

    /**
     * A `play` — spoken, or by touch (PROTOCOL.md "Browsing" step 3) — is about to end the talk:
     * [dropResumeAfterTalk] and [startNotBefore] ([notBeforeMs]) together, called before the talk
     * closes. Returns whether there was a resume to drop.
     */
    fun beforePlayEndsTalk(notBeforeMs: Long): Boolean {
        startNotBefore(notBeforeMs)
        return dropResumeAfterTalk()
    }

    /**
     * The next `music.play` is anchored no earlier than [atHostMs] (and started cold): a track
     * picked by a spoken `play` must not start before the usual resume lead after the talk it
     * closed, or both phones would start it into a headset still switching back from HFP.
     */
    fun startNotBefore(atHostMs: Long) {
        startNotBeforeMs = atHostMs
    }

    fun stop() {
        startJob?.cancel()
        starting = null
        servableId = null
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

    /**
     * A `music.error`. It ends the wait for `music.ready` (PROTOCOL.md "Music flow" step 3: the
     * host then plays alone) only when it is about the track whose `music.load` is being waited
     * for; any other — a prefetched next track, or a leftover from before the load was sent — is
     * logged as ignored. (First two-phone run, 2026-09-29: a `state` that named a track before it
     * was cached made the client fetch it, get a 404, and that error then started the host alone.)
     */
    fun onClientError(id: String, message: String) {
        val waiter = readyWaiters.remove(id)
        if (waiter != null) {
            log("music.error for $id: $message; playing without the client")
            waiter.complete(Unit)
        } else {
            log("music.error for $id ignored (no music.load awaiting it): $message")
        }
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
        sentNextId = null
        val t = current ?: return
        if (caches.cached(t.id) != null) send(load(t))
        upcoming.firstOrNull()?.let {
            if (caches.cached(it.id) != null) {
                sentNextId = it.id
                send(load(it))
            }
        }
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
        resumeCancelled = false
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
        resumeCancelled = false
        if (!pausedForTalk) return
        pausedForTalk = false
        val a = sync.anchor ?: return
        // Cold: the headset is on its way back from HFP to A2DP, so this start is late in a way
        // an ordinary one is not (SyncController.coldStartLatencyMs).
        playFrom(a.id, a.positionMs, resumeLeadMs, cold = true)
    }

    // ---- state ----

    /**
     * Why the current track is not playing when that is not the rider's own pause, for the UI:
     * still being fetched, waiting for the client's `music.ready`, or parked by an open talk
     * (it plays when the talk closes). Null when playing, plainly paused, or idle.
     */
    val phase: MusicPhase?
        get() {
            if (current == null) return null
            if (startJob?.isActive == true) starting?.let { return it }
            val a = sync.anchor
            return if (talkPausing && pausedForTalk && a != null && !a.playing) MusicPhase.PAUSED_FOR_TALK else null
        }

    /**
     * `state.music`, or null. Null also while the current track is still being fetched: `state`
     * must not name a track the client cannot download yet (PROTOCOL.md "Tracks": 404 until
     * cached), since a client prefetches the track `state` names.
     */
    fun musicState(): MusicState? {
        val t = current ?: return null
        if (t.id != servableId) return null
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
        startedId = null
        sync.apply(null)
        // Already cached (the prefetched next track, a restart): servable now, so no gap in state.
        servableId = t.id.takeIf { caches.cached(it) != null }
        starting = MusicPhase.LOADING
        onChanged()
        startJob = scope.launch {
            try {
                val file = caches.ensure(t.id)
                player.load(t, file)
                if (servableId != t.id) {
                    servableId = t.id
                    onChanged()
                }
                if (hasClient() && t.id !in clientReady) {
                    // A fresh waiter, never one left over from a start that was cancelled.
                    val waiter = CompletableDeferred<Unit>()
                    readyWaiters[t.id] = waiter
                    starting = MusicPhase.WAITING_CLIENT
                    try {
                        send(load(t))
                        if (withTimeoutOrNull(READY_TIMEOUT_MS) { waiter.await() } == null) {
                            Log.w(TAG, "no music.ready for ${t.id} within 8 s; playing alone")
                        }
                    } finally {
                        readyWaiters.remove(t.id, waiter)
                    }
                }
                starting = null
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
            // A track finished loading mid-talk (or `next`/`previous` picked it): park it until
            // talk closes — unless a `pause` in this talk said nothing should resume.
            sync.apply(Anchor(id, positionMs, hostNow(), playing = false))
            pausedForTalk = !resumeCancelled
            onChanged()
            return
        }
        val at = hostNow() + leadMs
        val late = startNotBeforeMs > at
        val a = Anchor(id, positionMs, if (late) startNotBeforeMs else at, playing = true)
        sync.apply(a, cold || late)
        send(MusicPlay(a.id, a.positionMs, a.atHostTimeMs))
        onChanged()
        if (startedId != id) {
            startedId = id
            current?.takeIf { it.id == id }?.let(onStarted)
        }
    }

    /**
     * As soon as the current track starts, cache the next [PREFETCH_AHEAD] and tell the client to
     * fetch the first of them (it only ever needs the next one). One at a time, in queue order, so
     * a patch of coverage is spent on the track that plays soonest and never races the current
     * one. A newer queue restarts it; a download already running finishes on its own (the cache
     * shares it with whoever asks next).
     */
    private fun prefetchNext() {
        val ahead = upcoming.take(PREFETCH_AHEAD)
        prefetchJob?.cancel()
        if (ahead.isEmpty()) return
        prefetchJob = scope.launch {
            for ((i, t) in ahead.withIndex()) {
                try {
                    caches.ensure(t.id)
                    if (i == 0 && hasClient() && sentNextId != t.id) {
                        sentNextId = t.id
                        send(load(t))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "prefetch ${t.id} failed", e)
                }
            }
        }
    }

    /** Fetch [t] from the active cache and send its `music.load` again, once per track. */
    private fun resend(t: Track) {
        if (!resent.add(t.id)) return
        scope.launch {
            runCatching { caches.ensure(t.id) }
                .onSuccess { if (hasClient()) send(load(t)) }
                .onFailure { Log.w(TAG, "re-fetch ${t.id} failed", it) }
        }
    }

    companion object {
        /** The spoken answer to `what's playing` (PROTOCOL.md "Commands"). */
        fun nowPlayingLine(t: Track?): String = when {
            t == null -> "Nothing playing"
            t.artist.isBlank() -> t.title
            else -> "${t.title} by ${t.artist}"
        }

        const val START_LEAD_MS = 300L
        const val READY_TIMEOUT_MS = 8_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val DUCK_VOLUME = 0.2f
        /** Upcoming tracks kept cached ahead of the current one, for patchy coverage. */
        const val PREFETCH_AHEAD = 3
        /** PROTOCOL.md: a `music.error` message starting with this = the client cannot play the file. */
        const val NOT_DECODABLE = "not decodable"
        private const val TAG = "MusicController"
    }
}

/** [MusicController.phase]: why the current track is not playing yet. */
enum class MusicPhase {
    /** The start job is fetching / caching the track. */
    LOADING,
    /** Cached and loaded here; waiting (up to 8 s) for the client's `music.ready`. */
    WAITING_CLIENT,
    /** Parked by an open talk: it plays when the talk closes. */
    PAUSED_FOR_TALK,
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

    /**
     * The upcoming tracks after [current] in a random order; the current one and those before it
     * stay. Never the same order back when a different one exists, so "Shuffled" is never a lie.
     */
    fun shuffled(queue: List<Track>, current: Int, random: Random): List<Track> {
        val head = queue.take(current + 1)
        val upcoming = queue.drop(current + 1)
        var mixed = upcoming.shuffled(random)
        if (mixed == upcoming && upcoming.distinct().size > 1) mixed = upcoming.drop(1) + upcoming.first()
        return head + mixed
    }

    /** The queue position of `upcoming[i]`, or null when that is not [id] any more. */
    fun at(queue: List<Track>, current: Int, i: Int, id: String): Int? {
        val at = current + 1 + i
        return at.takeIf { i >= 0 && queue.getOrNull(it)?.id == id }
    }
}
