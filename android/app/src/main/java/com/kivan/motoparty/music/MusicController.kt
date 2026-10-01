package com.kivan.motoparty.music

import android.util.Log
import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicLoad
import com.kivan.motoparty.core.MusicNext
import com.kivan.motoparty.core.MusicPause
import com.kivan.motoparty.core.MusicPlay
import com.kivan.motoparty.core.MusicState
import com.kivan.motoparty.core.MusicStop
import com.kivan.motoparty.core.RepeatMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
    /** Is there a network to download over ([NetworkWatch.online])? A load that fails without one waits. */
    private val online: () -> Boolean = { true },
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
    /**
     * A `pause` arrived while the current track was still loading or waiting for `music.ready`,
     * when there is no anchor to pause: [playFrom] parks the track paused instead of starting it.
     */
    private var pausedWhileStarting = false
    /** The start job is between [startCurrent] and its [playFrom], and no pause is waiting for it. */
    private val startingToPlay: Boolean
        get() = sync.anchor == null && startJob?.isActive == true && !pausedWhileStarting
    /** Tracks in a row that would not load; a start the rider asked for, or a load that works, resets it. */
    private var failStreak = 0
    /** ExoPlayer errors ([onPlayerError]) on [playerErrorId] since it was started. */
    private var playerErrors = 0
    private var playerErrorId: String? = null
    /** Completed by [onNetworkBack] for a start job that waits for the network. */
    private var networkBack: CompletableDeferred<Unit>? = null
    /** A talk is open, paused or ducked music alike. */
    private var talkOpen = false
    private var talkClosedAtMs: Long? = null

    /** The track a `music.error` already got its one re-sent `music.load` for (Music flow step 3). */
    private var errorReloadId: String? = null
    private var errorReloadJob: Job? = null

    /** Gapless (Music flow step 6): [id] sits behind the current track in the player and starts at [atHostTimeMs]. */
    private class Gapless(val id: String, val atHostTimeMs: Long)
    /** What was queued and announced with `music.next`; null when the change will be an ordinary start. */
    private var gapless: Gapless? = null
    private var gaplessRetry: Job? = null

    /** Is there a track after the current one? */
    val hasNext: Boolean get() = index + 1 < queue.size

    /**
     * `state.music.repeat` (2026-10-01): [RepeatMode.TRACK] starts the current track again when it
     * ends (an ordinary start, never gapless); [RepeatMode.QUEUE] goes on from the first track the
     * queue holds after the last one, instead of parking it.
     */
    var repeat: RepeatMode = RepeatMode.OFF
        private set

    /** [next] would park the last track: nothing after it and the queue does not repeat. */
    val atEnd: Boolean get() = !hasNext && !(repeat == RepeatMode.QUEUE && queue.isNotEmpty())

    /** Where the current track is now, in ms (0 when nothing is anchored). */
    val positionMs: Long
        get() = sync.anchor?.takeIf { it.id == current?.id }?.expectedAt(hostNow())?.coerceAtLeast(0) ?: 0

    init {
        player.onAdvanced = ::onAdvanced
    }

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
        else if (tracks.size == 1) caches.preResolve(listOf(tracks[0].id))
        armGapless()
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
     * A played track again (the `jump` voice action to −n): [track] goes right after the current
     * one and plays now; the rest of the queue stays. With nothing loaded it is the whole queue.
     */
    fun jumpBack(track: Track) {
        if (current == null) return setQueue(listOf(track), 0)
        queue = QueueEdits.inserted(queue, index, EnqueueMode.NEXT, listOf(track))
        index++
        startCurrent(0)
    }

    /**
     * `music.edit move` (drag to reorder): `upcoming[i]` taken out and put back so that it is
     * `upcoming[to]` afterwards (past the end = the end). False when [id] no longer sits at [i] (stale).
     */
    fun move(i: Int, id: String, to: Int): Boolean {
        queue = QueueEdits.moved(queue, index, i, id, to) ?: return false
        upcomingChanged(minOf(i, to))
        return true
    }

    /**
     * The upcoming list becomes [tracks] (a voice `remove`, `move` or `undo`, worked out on ids by
     * [VoiceEdits]); the current track plays on. One change.
     */
    fun replaceUpcoming(tracks: List<Track>) {
        val head = if (index < 0) emptyList() else queue.take(index + 1)
        val ahead = upcoming.take(PREFETCH_AHEAD)
        queue = head + tracks.take(QueueEdits.MAX_UPCOMING)
        upcomingChanged(if (upcoming.take(PREFETCH_AHEAD) != ahead) 0 else PREFETCH_AHEAD)
    }

    /** The upcoming list changed from position [from] on: tell the client, prefetch, and re-arm gapless. */
    private fun upcomingChanged(from: Int) {
        onChanged()
        if (from < PREFETCH_AHEAD) prefetchNext()
        armGapless()
    }

    /** Touch or voice: the repeat mode, sent in `state`. */
    fun setRepeat(mode: RepeatMode) {
        if (mode == repeat) return
        repeat = mode
        log("repeat ${mode.word}")
        onChanged()
        // A repeated track is never announced gapless; anything else may be again.
        armGapless()
    }

    /**
     * Move in the current track to [positionMs], clamped to it. Playing: started there, like any
     * play. Paused, or parked by a talk: only the anchor moves, and the resume (the talk's close)
     * starts from there. False with nothing anchored.
     */
    fun seek(positionMs: Long): Boolean {
        val t = current ?: return false
        val a = sync.anchor?.takeIf { it.id == t.id } ?: return false
        val length = player.durationMs ?: t.durationMs
        // Never onto the very end: that would only end the track.
        val pos = if (length > SEEK_END_MARGIN_MS) positionMs.coerceIn(0, length - SEEK_END_MARGIN_MS) else 0
        log("seek to $pos ms")
        if (a.playing) {
            playFrom(t.id, pos)
        } else {
            sync.apply(a.copy(positionMs = pos, atHostTimeMs = hostNow(), playing = false))
            onChanged()
        }
        return true
    }

    /**
     * Spoken `shuffle` (PROTOCOL.md "Commands"): shuffle the upcoming tracks, the current one plays
     * on. False, and nothing changes, with fewer than two upcoming.
     */
    fun shuffleUpcoming(random: Random = Random.Default): Boolean {
        if (upcoming.size < 2) return false
        queue = QueueEdits.shuffled(queue, index, random)
        onChanged()
        prefetchNext()
        armGapless()
        return true
    }

    /** `music.edit remove`: drop `upcoming[i]`. False when [id] no longer sits at [i] (stale). */
    fun remove(i: Int, id: String): Boolean {
        val at = QueueEdits.at(queue, index, i, id) ?: return false
        queue = queue.toMutableList().apply { removeAt(at) }
        onChanged()
        if (i < PREFETCH_AHEAD) prefetchNext()
        armGapless()
        return true
    }

    /** Undo of a [remove]: [track] back at `upcoming[i]` (the end when [i] is past it). One change. */
    fun insert(i: Int, track: Track) {
        if (current == null) return
        val at = (index + 1 + i).coerceIn(index + 1, queue.size)
        queue = queue.toMutableList().apply { add(at, track) }
        onChanged()
        if (at - index - 1 < PREFETCH_AHEAD) prefetchNext()
        armGapless()
    }

    /** `music.edit clear`: drop every upcoming track; the current one plays on. */
    fun clearUpcoming() {
        if (upcoming.isEmpty()) return
        queue = queue.take(index + 1)
        onChanged()
        prefetchNext()
        armGapless()
    }

    /**
     * The next track. On the last one the queue is kept and that track is parked ([parkAtEnd]), or,
     * with [RepeatMode.QUEUE], the queue starts again from its first track.
     */
    fun next() {
        if (!hasNext) {
            if (repeat != RepeatMode.QUEUE || queue.isEmpty()) return parkAtEnd()
            log("repeat queue: back to the first track")
            index = 0
            return startCurrent(0)
        }
        index++
        startCurrent(0)
    }

    /**
     * End of the queue (the last track ran out, or `next` on it): the last track stays loaded,
     * paused at its start, on both phones, so `previous` and a resume still have something to
     * work on. (Until 2026-09-30 this was [stop], which wiped queue and now-playing.) A last track
     * that is still loading is left to load.
     */
    private fun parkAtEnd() {
        val t = current ?: return
        val a = sync.anchor ?: return
        disarmGapless()
        pausedForTalk = false
        log("end of queue: ${t.id} parked paused at its start")
        sync.apply(a.copy(positionMs = 0, atHostTimeMs = hostNow(), playing = false))
        send(MusicPause(a.id, 0))
        onChanged()
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
        val a = sync.anchor
        if (a == null) {
            // Nothing to pause yet: remember it for the start job.
            val t = current
            if (t != null && startingToPlay) {
                pausedWhileStarting = true
                log("pause while starting ${t.id}: it will load paused")
                onChanged()
            }
            return
        }
        if (!a.playing) return
        disarmGapless()
        val pos = a.expectedAt(hostNow()).coerceAtLeast(0)
        log("pause at $pos ms")
        sync.apply(a.copy(positionMs = pos, atHostTimeMs = hostNow(), playing = false))
        send(MusicPause(a.id, pos))
        onChanged()
    }

    fun resume() {
        resumeCancelled = false
        if (pausedWhileStarting) {
            pausedWhileStarting = false
            log("resume while starting: the track plays when it is loaded")
            onChanged()
        }
        val a = sync.anchor
        if (a == null) {
            // A track that could not be loaded (and nothing to skip to): try it again.
            if (current != null && startJob?.isActive != true) startCurrent(0)
            return
        }
        if (a.playing) return
        if (player.loadedId != a.id) {
            // Unloaded by a player error: load it again, or there is nothing to play.
            val t = current?.takeIf { it.id == a.id }
            val file = caches.cached(a.id)
            if (t == null || file == null) return startCurrent(a.positionMs)
            player.load(t, file)
        }
        // playFrom parks it while talk holds the music; say so, or the log reads like a dead play.
        log(if (talkPausing) "resume parked: talk open (at ${a.positionMs} ms)" else "resume from ${a.positionMs} ms")
        playFrom(a.id, a.positionMs)
    }

    fun togglePlayPause() = if (isPlaying || startingToPlay) pause() else resume()

    /** Is there a track [resume] could start? */
    val canResume: Boolean get() = sync.anchor != null || current != null

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
        disarmGapless()
        errorReloadJob?.cancel()
        startJob?.cancel()
        starting = null
        servableId = null
        sync.apply(null)
        player.stop()
        queue = emptyList()
        index = -1
        prefetchJob?.cancel()
        cacheHints()
        send(MusicStop)
        onChanged()
    }

    fun onTrackEnded() {
        if (current == null || !isPlaying) return
        if (repeat == RepeatMode.TRACK) {
            log("repeat track: ${current?.id} again")
            return startCurrent(0)
        }
        next()
    }

    /**
     * [LocalPlayer.onAdvanced]: the player went on to the track [queueNext][LocalPlayer.queueNext]
     * put behind the current one. Nothing is paused, loaded or prepared: the queue moves on, the
     * anchor becomes the one `music.next` announced, and the client gets `state`, the usual
     * `music.play` with that same anchor, and the `music.load` of the track after it.
     */
    private fun onAdvanced(id: String) {
        val g = gapless
        gapless = null
        val t = queue.getOrNull(index + 1)
        if (g == null || g.id != id || t?.id != id || !isPlaying) {
            // Every disarm takes the queued item out of the player, so this should not happen.
            log("gapless: unexpected change to $id; starting the next track the ordinary way")
            player.clearNext()
            if (current != null) next()
            return
        }
        index++
        pausedWhileStarting = false
        failStreak = 0
        playerErrorId = null
        errorReloadId = null
        errorReloadJob?.cancel()
        servableId = id
        startedId = id
        log("gapless: now $id, ${hostNow() - g.atHostTimeMs} ms after its anchor")
        sync.adopt(Anchor(id, 0, g.atHostTimeMs, playing = true))
        onChanged()
        send(MusicPlay(id, 0, g.atHostTimeMs))
        onStarted(t)
        prefetchNext()
        armGapless()
    }

    /**
     * ExoPlayer failed on the loaded track ([Player] has unloaded it; it is silent while the
     * anchor may still say playing). The first time the track is loaded again and put back on the
     * anchor as a cold start, so the client is not disturbed; the second time on the same track
     * it is skipped.
     */
    fun onPlayerError(message: String) {
        val t = current ?: return
        if (playerErrorId != t.id) {
            playerErrorId = t.id
            playerErrors = 0
        }
        playerErrors++
        val a = sync.anchor
        log("player error $playerErrors on ${t.id}: $message")
        // The player dropped what was queued behind the track; the client is told if it goes on.
        disarmGapless(tellClient = true)
        // What is not playing because someone paused it must not start playing through this.
        if (a != null && !a.playing && !pausedForTalk) pausedWhileStarting = true
        if (playerErrors >= MAX_PLAYER_ERRORS) {
            val next = nextPlayable()
            if (next == null) {
                onError("Couldn't play ${t.title}")
                pausedWhileStarting = false
                if (a != null) return pause()
                startJob?.cancel()
                starting = null
                onChanged()
                return
            }
            onError("Couldn't play ${t.title}. Skipping")
            index = next
            return startCurrent(0, internal = true)
        }
        // Still loading or waiting for the client: start it over.
        if (a == null) {
            if (startJob?.isActive == true) startCurrent(0, internal = true)
            return
        }
        val file = caches.cached(t.id)
        if (file == null) {
            // Evicted under the player: fetch it again and go on from where it should be.
            return startCurrent(a.expectedAt(hostNow()).coerceAtLeast(0), internal = true)
        }
        pausedWhileStarting = false
        player.load(t, file)
        sync.apply(a, cold = true)
        armGapless()
    }

    /**
     * `ACTION_AUDIO_BECOMING_NOISY`: the earbuds dropped and the music would move to the phone's
     * speaker. It is shared music, so it pauses for both phones. Not while a talk is open or has
     * just closed: the app itself moves the headset between A2DP and HFP then, the music is
     * already handled by the talk, and a [pause] there would cancel the resume the talk holds.
     */
    fun onBecomingNoisy() {
        val closedAt = talkClosedAtMs
        if (talkOpen || (closedAt != null && hostNow() - closedAt < NOISY_GUARD_MS)) {
            log("becoming noisy ignored: talk ${if (talkOpen) "open" else "just closed"}")
            return
        }
        if (!isPlaying && !startingToPlay) return
        log("becoming noisy: pausing the music")
        pause()
    }

    /** The network is back ([NetworkWatch]): retry the load that waits for it, and the prefetch. */
    fun onNetworkBack() {
        networkBack?.complete(Unit)
        if (current != null && startJob?.isActive != true) prefetchNext()
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
        if (joinMidTrack && a != null) {
            send(MusicPlay(a.id, a.positionMs, a.atHostTimeMs))
            // Any music.play cancels the client's pending music.next (Music flow step 6): say it again.
            gapless?.let { send(MusicNext(it.id, it.atHostTimeMs)) }
        }
        armGapless()
    }

    /**
     * A `music.error`. It ends the wait for `music.ready` (PROTOCOL.md "Music flow" step 3: the
     * host then plays alone) only when it is about the track whose `music.load` is being waited
     * for, and only the second time: after the first one the current track's `music.load` is sent
     * once more, [ERROR_RELOAD_MS] later (the client then joins mid-track if the host has started
     * alone meanwhile). Any other — a prefetched next track, or a leftover from before the load was sent — is
     * logged as ignored. (First two-phone run, 2026-09-29: a `state` that named a track before it
     * was cached made the client fetch it, get a 404, and that error then started the host alone.)
     */
    fun onClientError(id: String, message: String) {
        val loadSent = readyWaiters.containsKey(id) || sync.anchor?.id == id
        if (id == current?.id && loadSent && errorReloadId != id && !message.startsWith(NOT_DECODABLE)) {
            errorReloadId = id
            log("music.error for $id: $message; sending music.load again in ${ERROR_RELOAD_MS / 1000} s")
            errorReloadJob?.cancel()
            errorReloadJob = scope.launch {
                delay(ERROR_RELOAD_MS)
                val t = current?.takeIf { it.id == id } ?: return@launch
                if (!hasClient() || caches.cached(id) == null) return@launch
                log("music.load for $id sent again")
                send(load(t))
            }
            return
        }
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
        errorReloadId = null
        // The new client was not told of it, and has not said it is ready for the next track.
        disarmGapless()
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
        armGapless()
    }

    // ---- talk ----

    fun onTalkOpen(duck: Boolean) {
        talkOpen = true
        // A talk cancels a pending music.next on the client too; it is sent again afterwards.
        disarmGapless()
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
        talkOpen = false
        talkClosedAtMs = hostNow()
        if (duckedForTalk) {
            player.volume = 1f
            duckedForTalk = false
        }
        talkPausing = false
        resumeCancelled = false
        if (!pausedForTalk) return armGapless()
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
            repeat = repeat.wire,
        )
    }

    // ---- internals ----

    private fun load(t: Track) = MusicLoad(t.id, t.path, t.title, t.artist, t.album, t.durationMs)

    /**
     * [internal]: not the rider's choice but a skip or restart after a failure, which keeps a
     * waiting pause and the failure counts.
     */
    private fun startCurrent(positionMs: Long, internal: Boolean = false) {
        val t = current ?: return
        startJob?.cancel()
        disarmGapless()
        errorReloadJob?.cancel()
        if (!internal) {
            pausedWhileStarting = false
            failStreak = 0
            playerErrorId = null
            errorReloadId = null
        }
        startedId = null
        sync.apply(null)
        // Downloads of tracks that are no longer wanted stop now, not behind the new one.
        cacheHints()
        // Already cached (the prefetched next track, a restart): servable now, so no gap in state.
        servableId = t.id.takeIf { caches.cached(it) != null }
        starting = MusicPhase.LOADING
        onChanged()
        startJob = scope.launch {
            val file = try {
                fetch(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not start ${t.id}", e)
                return@launch skipUnloadable(t, e)
            }
            failStreak = 0
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
            playFrom(t.id, positionMs)
            prefetchNext()
        }
    }

    /**
     * The file of [t]. The cache has already retried each chunk; a failure that still comes
     * through while there is no network is waited out (the track is kept), any other is thrown.
     */
    private suspend fun fetch(t: Track): File {
        var told = false
        while (true) {
            try {
                return caches.ensure(t.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (online()) throw e
                log("load ${t.id} failed with no network ($e); waiting for it")
                if (!told) onError("No network. ${t.title} plays when it is back")
                told = true
                val back = CompletableDeferred<Unit>().also { networkBack = it }
                // The timeout is for a network callback that never comes.
                withTimeoutOrNull(OFFLINE_RETRY_MS) { back.await() }
            }
        }
    }

    /** [t] cannot be loaded: go on with the next track that can be, so the queue does not die. */
    private fun skipUnloadable(t: Track, e: Exception) {
        failStreak++
        val next = nextPlayable()
        if (next == null) {
            log("load ${t.id} failed ($e); nothing to skip to")
            starting = null
            onError("Couldn't load ${t.title}")
            onChanged()
            return
        }
        log("load ${t.id} failed ($e); skipping to ${queue[next].id}")
        onError("Couldn't load ${t.title}. Skipping")
        index = next
        startCurrent(0, internal = true)
    }

    /**
     * Where to go when the current track cannot be played: the first upcoming track that is
     * already cached, else simply the next one, until [MAX_SKIPS] in a row have failed.
     */
    private fun nextPlayable(): Int? {
        val from = index + 1
        if (from >= queue.size) return null
        return (from until queue.size).firstOrNull { caches.cached(queue[it].id) != null }
            ?: from.takeIf { failStreak < MAX_SKIPS }
    }

    /**
     * Anchor [id] at [positionMs] and tell the client. The anchor is far enough ahead for this
     * phone to be audible at it ([SyncController.startLeadMs]; until 2026-09-30 a fixed 300 ms,
     * which made the host skip the first 250–750 ms of every start), and at least [leadMs].
     */
    private fun playFrom(id: String, positionMs: Long, leadMs: Long = 0, cold: Boolean = false) {
        disarmGapless()
        if (talkPausing || pausedWhileStarting) {
            // A track finished loading mid-talk (or `next`/`previous` picked it): park it until
            // talk closes — unless a `pause` in this talk said nothing should resume. A pause
            // that came while it was loading parks it the same way, as an ordinary pause.
            sync.apply(Anchor(id, positionMs, hostNow(), playing = false))
            pausedForTalk = talkPausing && !resumeCancelled && !pausedWhileStarting
            if (pausedWhileStarting) log("parked paused at $positionMs ms: paused while starting")
            pausedWhileStarting = false
            onChanged()
            return
        }
        val now = hostNow()
        val late = startNotBeforeMs > now + maxOf(leadMs, sync.startLeadMs(cold))
        val isCold = cold || late
        val lead = maxOf(leadMs, sync.startLeadMs(isCold))
        val a = Anchor(id, positionMs, maxOf(now + lead, startNotBeforeMs), playing = true)
        log("play $id from $positionMs ms, anchor in ${a.atHostTimeMs - now} ms${if (isCold) " (cold)" else ""}")
        sync.apply(a, isCold)
        send(MusicPlay(a.id, a.positionMs, a.atHostTimeMs))
        onChanged()
        if (startedId != id) {
            startedId = id
            current?.takeIf { it.id == id }?.let(onStarted)
        }
        armGapless()
    }

    /**
     * PROTOCOL.md "Music flow" step 6. When the current track is playing, no talk is open and the
     * next one is cached here and ready on the client (or there is no client): queue it behind
     * the current one in the player and announce it with `music.next`, starting when the current
     * track ends by its anchor. Called whenever one of those may have changed; when they no longer
     * hold, what was queued is taken back and the change is an ordinary start ([onTrackEnded]).
     */
    private fun armGapless() {
        gaplessRetry?.cancel()
        gaplessRetry = null
        val t = current
        val a = sync.anchor
        val next = upcoming.firstOrNull()
        if (t == null || a == null || next == null || !a.playing || a.id != t.id || talkOpen ||
            player.loadedId != t.id || next.id == t.id || repeat == RepeatMode.TRACK
        ) return disarmGapless(tellClient = true)
        val file = caches.cached(next.id) ?: return disarmGapless(tellClient = true)
        if (hasClient() && next.id !in clientReady) return disarmGapless(tellClient = true)
        // The player's own figure: that is when it really changes. The catalog's only when the
        // player is prepared and still has none.
        val duration = player.durationMs ?: t.durationMs.takeIf { it > 0 && player.isReady }
        if (duration == null) {
            gaplessRetry = scope.launch {
                delay(GAPLESS_RETRY_MS)
                armGapless()
            }
            return
        }
        val at = a.atHostTimeMs + (duration - a.positionMs)
        gapless?.let { if (it.id == next.id && it.atHostTimeMs == at) return }
        if (at - hostNow() < GAPLESS_MIN_NOTICE_MS || !player.queueNext(next, file)) {
            return disarmGapless(tellClient = true)
        }
        gapless = Gapless(next.id, at)
        log("gapless: ${next.id} queued behind ${t.id}, change in ${at - hostNow()} ms")
        send(MusicNext(next.id, at))
    }

    /**
     * Take back what [armGapless] queued. [tellClient]: nothing else the host sends will cancel
     * the client's pending `music.next` (a queue edit, a track that stopped being ready), so the
     * current track's `music.play` is sent again with its unchanged anchor, which does.
     */
    private fun disarmGapless(tellClient: Boolean = false) {
        gaplessRetry?.cancel()
        gaplessRetry = null
        if (gapless == null) return
        gapless = null
        player.clearNext()
        val a = sync.anchor
        if (tellClient && a != null && a.playing && !talkOpen && hasClient()) {
            log("gapless: cancelled")
            send(MusicPlay(a.id, a.positionMs, a.atHostTimeMs))
        }
    }

    /**
     * As soon as the current track starts, cache the next [PREFETCH_AHEAD] and tell the client to
     * fetch the first of them (it only ever needs the next one). One at a time, in queue order, so
     * a patch of coverage is spent on the track that plays soonest and never races the current
     * one (the cache also runs one download at a time, by [DownloadPriority]). A newer queue
     * restarts it: cancelling the job stops its download, and the next [TrackStore.ensure] for
     * that track goes on from the bytes already there.
     */
    private fun prefetchNext() {
        val ahead = upcoming.take(PREFETCH_AHEAD)
        prefetchJob?.cancel()
        cacheHints()
        if (ahead.isEmpty()) return
        prefetchJob = scope.launch {
            for ((i, t) in ahead.withIndex()) {
                try {
                    caches.ensure(t.id, if (i == 0) DownloadPriority.NEXT else DownloadPriority.PREFETCH)
                    if (i == 0 && hasClient() && sentNextId != t.id) {
                        sentNextId = t.id
                        send(load(t))
                    }
                    if (i == 0) armGapless()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "prefetch ${t.id} failed", e)
                }
            }
        }
    }

    /**
     * Tell the cache what the queue needs now: downloads of other tracks stop (album downloads
     * are not touched), the playing track and the next one are never evicted, and the stream
     * addresses of the tracks ahead are looked up before they are needed.
     */
    private fun cacheHints() {
        val ahead = upcoming.take(PREFETCH_AHEAD).map { it.id }
        caches.retain(listOfNotNull(current?.id) + ahead)
        caches.protect(listOfNotNull(current?.id, ahead.firstOrNull()))
        if (current != null) caches.preResolve(ahead)
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

        /** The least lead of a `music.play`; the real one is [SyncController.startLeadMs]. */
        const val START_LEAD_MS = SyncController.MIN_START_LEAD_MS
        /** A `music.error` for the current track: its `music.load` goes out again this much later. */
        const val ERROR_RELOAD_MS = 2_000L
        /** Less than this before the current track ends, the next one is not announced any more. */
        const val GAPLESS_MIN_NOTICE_MS = 1_500L
        /** The player did not know the track's length yet: ask again this much later. */
        const val GAPLESS_RETRY_MS = 1_000L
        const val READY_TIMEOUT_MS = 8_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        /** A seek stops this far before the end of the track. */
        const val SEEK_END_MARGIN_MS = 1_000L
        const val DUCK_VOLUME = 0.2f
        /** Upcoming tracks kept cached ahead of the current one, for patchy coverage. */
        const val PREFETCH_AHEAD = 3
        /** Tracks in a row that may fail to load before the queue stops trying. */
        const val MAX_SKIPS = 3
        /** ExoPlayer errors on one track before it is skipped. */
        const val MAX_PLAYER_ERRORS = 2
        /** How often a load that waits for the network tries anyway. */
        const val OFFLINE_RETRY_MS = 30_000L
        /** After a talk closes the headset is still switching back to A2DP; see [onBecomingNoisy]. */
        const val NOISY_GUARD_MS = 3_000L
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

    /**
     * `music.edit move`: `upcoming[i]` taken out and put back so that it is `upcoming[to]`
     * afterwards (past the end = the end); null when that is not [id] any more.
     */
    fun moved(queue: List<Track>, current: Int, i: Int, id: String, to: Int): List<Track>? {
        val at = at(queue, current, i, id) ?: return null
        if (to < 0) return null
        val head = queue.take(current + 1)
        val upcoming = queue.drop(current + 1).toMutableList()
        val t = upcoming.removeAt(at - current - 1)
        upcoming.add(to.coerceAtMost(upcoming.size), t)
        return head + upcoming
    }

    /** The queue position of `upcoming[i]`, or null when that is not [id] any more. */
    fun at(queue: List<Track>, current: Int, i: Int, id: String): Int? {
        val at = current + 1 + i
        return at.takeIf { i >= 0 && queue.getOrNull(it)?.id == id }
    }
}
