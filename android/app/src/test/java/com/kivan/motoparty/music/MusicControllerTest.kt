package com.kivan.motoparty.music

import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicLoad
import com.kivan.motoparty.core.MusicNext
import com.kivan.motoparty.core.MusicPause
import com.kivan.motoparty.core.MusicPlay
import com.kivan.motoparty.core.MusicState
import com.kivan.motoparty.core.RepeatMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The load -> ready -> play handshake against a client that prefetches whatever `state` names.
 * First two-phone run (2026-09-29): `state` named a track before the host had cached it, the
 * client's prefetch got `HTTP 404`, and its `music.error` — arriving once the host was waiting for
 * `music.ready` — made the host start the track without the client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicControllerTest {

    private class FakePlayer : LocalPlayer {
        override var loadedId: String? = null
        override val isReady = true
        override var isPlaying = false
        override val positionMs = 0L
        override var speed = 1f
        override var volume = 1f
        override fun play() { isPlaying = true }
        override fun pause() { isPlaying = false }
        val seeks = mutableListOf<Long>()
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        val loads = mutableListOf<String>()
        override fun load(track: Track, file: File) { loadedId = track.id; loads += track.id; queued = null }
        override fun stop() { loadedId = null; queued = null }

        /** The track queued behind the loaded one (gapless). */
        var queued: String? = null
        override fun queueNext(track: Track, file: File): Boolean { queued = track.id; return true }
        override fun clearNext() { queued = null }
        override var durationMs: Long? = null
        override var onAdvanced: ((id: String) -> Unit)? = null
        /** The loaded track ran out and the queued one took over, as ExoPlayer reports it. */
        fun advance() {
            val id = checkNotNull(queued)
            queued = null
            loadedId = id
            onAdvanced?.invoke(id)
        }
    }

    /** A cache whose downloads finish only when the test says so. */
    private class FakeStore : TrackStore {
        override var opus = true
        val files = HashMap<String, File>()
        val downloads = HashMap<String, CompletableDeferred<Unit>>()
        override suspend fun ensure(id: String): File {
            files[id]?.let { return it }
            ensured += id
            val download = downloads.getOrPut(id) { CompletableDeferred() }
            try {
                download.await()
            } catch (e: java.io.IOException) {
                downloads.remove(id, download)
                throw e
            }
            return File("$id.m4a").also { files[id] = it }
        }
        override fun cached(id: String): File? = files[id]
        val priorities = HashMap<String, DownloadPriority>()
        override suspend fun ensure(id: String, priority: DownloadPriority): File {
            priorities[id] = priority
            return ensure(id)
        }
        var retained: List<String> = emptyList()
        var protected: List<String> = emptyList()
        val preResolved = mutableListOf<String>()
        override fun retain(ids: Collection<String>) { retained = ids.toList() }
        override fun protect(ids: Collection<String>) { protected = ids.toList() }
        override fun preResolve(ids: Collection<String>) { preResolved += ids }
        /** Every [ensure] that had to download. */
        val ensured = mutableListOf<String>()
        fun fail(id: String) = downloads.getOrPut(id) { CompletableDeferred() }.completeExceptionally(java.io.IOException("HTTP 503"))
        fun finish(id: String) = downloads.getOrPut(id) { CompletableDeferred() }.complete(Unit)
    }

    private class Rig(scope: TestScope) {
        val store = FakeStore()
        val sent = mutableListOf<Message>()
        /** `state.music` at every push, as the client would see it. */
        val states = mutableListOf<MusicState?>()
        val log = mutableListOf<String>()
        val started = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var online = true
        var client = true
        val player = FakePlayer()
        val sync = SyncController(player, scope.backgroundScope, hostNow = { scope.currentTime }, trimMs = { 0 })
            .apply { traceLog = null; log = {} }
        lateinit var music: MusicController
        init {
            music = MusicController(
                scope.backgroundScope, store, sync, player, hostNow = { scope.currentTime },
                send = { sent += it }, hasClient = { client },
                onChanged = { states += music.musicState() },
                onError = { errors += it }, log = { log += it },
                onStarted = { started += it.id },
                online = { online },
            )
        }
        val plays get() = sent.filterIsInstance<MusicPlay>()
        val loads get() = sent.filterIsInstance<MusicLoad>()
        val nexts get() = sent.filterIsInstance<MusicNext>()
        val pauses get() = sent.filterIsInstance<MusicPause>()
    }

    private val a = Track("aaaaaaaaaaa", "A", "Artist", durationMs = 200_000)
    private val b = Track("bbbbbbbbbbb", "B", "Artist", durationMs = 200_000)
    private val c = Track("ccccccccccc", "C", "", durationMs = 200_000)
    private val d = Track("ddddddddddd", "D", "Artist", durationMs = 200_000)
    private val e = Track("eeeeeeeeeee", "E", "Artist", durationMs = 200_000)

    /** Start [first] with the client ready, so it plays. */
    private fun kotlinx.coroutines.test.TestScope.playing(rig: Rig, first: Track) {
        rig.store.finish(first.id)
        runCurrent()
        rig.music.onClientReady(first.id)
        runCurrent()
    }

    @Test
    fun `the cache is told what the queue needs`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b, c, d, e))
        runCurrent()
        // Before the current track's download: the others of an older queue stop now.
        assertEquals(listOf(a.id, b.id, c.id, d.id), rig.store.retained)
        assertEquals(listOf(a.id, b.id), rig.store.protected)
        assertTrue(rig.store.preResolved.containsAll(listOf(b.id, c.id, d.id)))
        playing(rig, a)
        rig.store.finish(b.id)
        runCurrent()
        assertEquals(null, rig.store.priorities[a.id])
        assertEquals(DownloadPriority.NEXT, rig.store.priorities[b.id])
        assertEquals(DownloadPriority.PREFETCH, rig.store.priorities[c.id])

        rig.music.next()
        runCurrent()
        assertEquals(listOf(b.id, c.id, d.id, e.id), rig.store.retained)
        assertEquals(listOf(b.id, c.id), rig.store.protected)

        rig.music.clearUpcoming()
        assertEquals(listOf(b.id), rig.store.retained)
        assertEquals(listOf(b.id), rig.store.protected)
    }

    @Test
    fun `insert puts a removed track back where it was, with one state push`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b, c, d))
        rig.store.finish(a.id)
        runCurrent()
        assertTrue(rig.music.remove(1, c.id))
        val pushes = rig.states.size
        rig.music.insert(1, c)
        assertEquals(listOf(b, c, d), rig.music.upcoming)
        assertEquals(pushes + 1, rig.states.size)
        rig.music.insert(0, e)
        rig.music.insert(99, e)
        assertEquals(listOf(e, b, c, d, e), rig.music.upcoming)
    }

    @Test
    fun `a mid-track join repeats the announced next track`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientError(a.id, "HTTP 500")
        rig.music.onClientError(a.id, "HTTP 500")
        runCurrent()
        rig.store.finish(b.id)
        runCurrent()
        rig.music.onClientReady(b.id)
        assertEquals(1, rig.nexts.size)
        rig.sent.clear()
        rig.music.onClientReady(a.id)
        // music.play cancels the client's pending music.next, so it follows again.
        assertEquals(listOf("MusicPlay", "MusicNext"), rig.sent.map { it::class.simpleName })
        assertEquals(b.id, rig.nexts.single().id)
    }

    @Test
    fun `state does not name a track before it can be served`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        assertEquals("pushed while downloading, without the track", listOf<MusicState?>(null), rig.states)
        assertTrue(rig.loads.isEmpty())

        rig.store.finish(a.id)
        runCurrent()
        assertEquals(a.id, rig.states.last()?.id)
        assertEquals(listOf(a.id), rig.loads.map { it.id })
    }

    @Test
    fun `a stale or unrelated music error does not start the track without the client`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        // Before the load was sent (the old 404 of a prefetch from `state`): nobody waits on it.
        rig.music.onClientError(a.id, "HTTP 404")
        rig.store.finish(a.id)
        runCurrent()
        assertEquals(listOf(a.id), rig.loads.map { it.id })
        // An error about another track while the host waits for A's music.ready.
        rig.music.onClientError(b.id, "HTTP 404")
        advanceTimeBy(1_000)
        assertTrue("must still wait for the client: ${rig.sent}", rig.plays.isEmpty())
        assertEquals(2, rig.log.count { it.contains("ignored (no music.load awaiting it)") })

        rig.music.onClientReady(a.id)
        runCurrent()
        assertEquals(listOf(a.id), rig.plays.map { it.id })
    }

    // ---- M9: one more music.load after a music.error ----

    @Test
    fun `an error about the loaded track gets one more load, a second one lets the host play alone`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientError(a.id, "HTTP 500")
        runCurrent()
        assertTrue("still waiting for the client: ${rig.sent}", rig.plays.isEmpty())
        assertEquals("music.error for ${a.id}: HTTP 500; sending music.load again in 2 s", rig.log.last())
        advanceTimeBy(MusicController.ERROR_RELOAD_MS - 1)
        assertEquals(1, rig.loads.size)
        advanceTimeBy(2)
        assertEquals(listOf(a.id, a.id), rig.loads.map { it.id })

        rig.music.onClientError(a.id, "HTTP 500")
        runCurrent()
        assertEquals(listOf(a.id), rig.plays.map { it.id })
        assertTrue("music.error for ${a.id}: HTTP 500; playing without the client" in rig.log)
        advanceTimeBy(10_000)
        assertEquals("one retry only", 2, rig.loads.size)
    }

    @Test
    fun `the client that answers the second load plays with the host`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientError(a.id, "timed out")
        advanceTimeBy(MusicController.ERROR_RELOAD_MS + 1)
        rig.music.onClientReady(a.id)
        runCurrent()
        assertEquals(1, rig.plays.size)
        assertEquals(currentTime + rig.sync.startLeadMs(), rig.plays.single().atHostTimeMs)
    }

    @Test
    fun `an error once the host plays alone gets the load again and the client joins mid-track`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        advanceTimeBy(MusicController.READY_TIMEOUT_MS + 1)
        assertEquals(1, rig.plays.size)
        rig.music.onClientError(a.id, "HTTP 503")
        advanceTimeBy(MusicController.ERROR_RELOAD_MS + 1)
        assertEquals(2, rig.loads.size)
        rig.music.onClientReady(a.id)
        assertEquals("the same anchor again", listOf(rig.plays[0], rig.plays[0]), rig.plays)
    }

    @Test
    fun `not decodable is not retried as it is - the host plays alone at once`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientError(a.id, "${MusicController.NOT_DECODABLE}: opus")
        runCurrent()
        assertEquals(listOf(a.id), rig.plays.map { it.id })
        assertFalse(rig.store.opus)
    }

    @Test
    fun `a cancelled start leaves no waiter behind for the next start of the same track`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.jump(0, b.id) // cancels A's wait for music.ready
        runCurrent()
        // A late answer to the cancelled load: nobody waits on it any more.
        rig.music.onClientError(a.id, "HTTP 404")
        rig.music.previous() // back to A: a new load, a new wait
        runCurrent()
        assertEquals(listOf(a.id, a.id), rig.loads.map { it.id })
        assertTrue("must wait for A's ready: ${rig.sent}", rig.plays.isEmpty())
        rig.music.onClientReady(a.id)
        runCurrent()
        assertEquals(listOf(a.id), rig.plays.map { it.id })
    }

    @Test
    fun `pause and resume say where`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientReady(a.id)
        runCurrent()
        advanceTimeBy(5_000 + rig.sync.startLeadMs())
        rig.music.pause()
        assertEquals("pause at 5000 ms", rig.log.last())
        rig.music.resume()
        assertTrue("resume from 5000 ms" in rig.log)
    }

    @Test
    fun `phase follows the start - loading, waiting for the client, then nothing once playing`() = runTest {
        val rig = Rig(this)
        assertNull(rig.music.phase)
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        assertEquals(MusicPhase.LOADING, rig.music.phase)
        rig.store.finish(a.id)
        runCurrent()
        assertEquals(MusicPhase.WAITING_CLIENT, rig.music.phase)
        rig.music.onClientReady(a.id)
        runCurrent()
        assertTrue(rig.music.isPlaying)
        assertNull(rig.music.phase)
        rig.music.pause()
        assertNull("a plain pause is not a phase", rig.music.phase)
    }

    @Test
    fun `phase ends when the client never answers and the host plays alone`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        assertEquals(MusicPhase.WAITING_CLIENT, rig.music.phase)
        advanceTimeBy(MusicController.READY_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(rig.music.isPlaying)
        assertNull(rig.music.phase)
    }

    @Test
    fun `a track that finishes loading in a talk is parked, and a resume says why`() = runTest {
        val rig = Rig(this)
        rig.music.onTalkOpen(duck = false)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientReady(a.id)
        runCurrent()
        assertTrue(rig.plays.isEmpty())
        assertEquals(MusicPhase.PAUSED_FOR_TALK, rig.music.phase)
        rig.music.resume()
        assertEquals("resume parked: talk open (at 0 ms)", rig.log.last())
        assertTrue(rig.plays.isEmpty())
        assertEquals(MusicPhase.PAUSED_FOR_TALK, rig.music.phase)

        rig.music.onTalkClose(resumeLeadMs = 1_500)
        assertEquals(listOf(a.id), rig.plays.map { it.id })
        assertNull(rig.music.phase)
    }

    @Test
    fun `music paused by a talk shows the phase, and a spoken pause clears it`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientReady(a.id)
        runCurrent()
        rig.music.onTalkOpen(duck = false)
        assertEquals(MusicPhase.PAUSED_FOR_TALK, rig.music.phase)
        rig.music.pause()
        assertNull("nothing resumes after this talk: plainly paused", rig.music.phase)
    }

    @Test
    fun `a ducked talk is no phase`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientReady(a.id)
        runCurrent()
        rig.music.onTalkOpen(duck = true)
        assertTrue(rig.music.isPlaying)
        assertNull(rig.music.phase)
    }

    @Test
    fun `the next three tracks are cached one at a time, and only the next is sent to the client`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b, c, d, e))
        playing(rig, a)
        assertEquals("one download at a time, next first", setOf(a.id, b.id), rig.store.downloads.keys)
        rig.store.finish(b.id)
        runCurrent()
        assertEquals(setOf(a.id, b.id, c.id), rig.store.downloads.keys)
        rig.store.finish(c.id)
        runCurrent()
        rig.store.finish(d.id)
        runCurrent()
        assertEquals("three ahead, not four", setOf(a.id, b.id, c.id, d.id), rig.store.downloads.keys)
        assertEquals(listOf(a.id, b.id), rig.loads.map { it.id })
    }

    @Test
    fun `now playing names the track, or says there is none`() {
        assertEquals("Nothing playing", MusicController.nowPlayingLine(null))
        assertEquals("A by Artist", MusicController.nowPlayingLine(a))
        assertEquals("C", MusicController.nowPlayingLine(c))
    }

    @Test
    fun `shuffle reorders what is upcoming, keeps the current track, and says when it cannot`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        playing(rig, a)
        assertFalse("one upcoming: nothing to shuffle", rig.music.shuffleUpcoming())
        rig.music.enqueue(EnqueueMode.END, listOf(c, d, e))
        val pushes = rig.states.size
        assertTrue(rig.music.shuffleUpcoming(kotlin.random.Random(3)))
        assertEquals(a, rig.music.current)
        assertEquals(listOf(b, c, d, e).map { it.id }.sorted(), rig.music.upcoming.map { it.id }.sorted())
        assertTrue("the client gets a new state", rig.states.size > pushes)
    }

    @Test
    fun `history hears of a track once it really starts, not on a resume`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        assertTrue("still loading", rig.started.isEmpty())
        playing(rig, a)
        rig.music.pause()
        rig.music.resume()
        assertEquals(listOf(a.id), rig.started)
        rig.store.finish(b.id)
        rig.music.next()
        runCurrent()
        rig.music.onClientReady(b.id)
        runCurrent()
        assertEquals(listOf(a.id, b.id), rig.started)
    }

    @Test
    fun `a track parked by a talk is not started until the talk ends`() = runTest {
        val rig = Rig(this)
        rig.music.onTalkOpen(duck = false)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        assertTrue(rig.started.isEmpty())
        rig.music.onTalkClose(resumeLeadMs = 1_500)
        assertEquals(listOf(a.id), rig.started)
    }

    /**
     * PROTOCOL.md "Browsing" step 3, in the order LinkHost.playByTouch runs it: a play by touch
     * during a talk drops the music the talk paused, closes the talk, and the new track starts no
     * earlier than the resume lead.
     */
    @Test
    fun `play by touch in a talk ends it like a spoken play`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        rig.music.onTalkOpen(duck = false)
        val touchedAt = currentTime
        assertTrue("A was paused by the talk", rig.music.beforePlayEndsTalk(touchedAt + 1_500))
        rig.music.onTalkClose(resumeLeadMs = 1_500)
        val playsBefore = rig.plays.size
        rig.music.enqueue(EnqueueMode.NOW, listOf(b))
        rig.store.finish(b.id)
        runCurrent()
        rig.music.onClientReady(b.id)
        runCurrent()
        val after = rig.plays.drop(playsBefore)
        assertEquals("A does not come back first", listOf(b.id), after.map { it.id })
        assertTrue("after the headset switch: ${after.single()}", after.single().atHostTimeMs >= touchedAt + 1_500)
    }

    // ---- R9: a pause while the track is still starting ----

    @Test
    fun `a pause during the download parks the track paused and resume starts it`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        rig.music.pause()
        playing(rig, a)
        assertTrue("must not play: ${rig.sent}", rig.plays.isEmpty())
        assertEquals(false, rig.sync.anchor?.playing)
        assertEquals(false, rig.states.last()?.playing)
        assertTrue(rig.started.isEmpty())

        rig.music.resume()
        runCurrent()
        assertEquals(listOf(a.id), rig.plays.map { it.id })
        assertEquals(listOf(a.id), rig.started)
    }

    @Test
    fun `a pause during the music ready wait is kept and a toggle undoes it`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.togglePlayPause()
        rig.music.onClientReady(a.id)
        runCurrent()
        assertTrue(rig.plays.isEmpty())
        assertFalse(rig.music.isPlaying)

        rig.music.togglePlayPause()
        assertEquals(listOf(a.id), rig.plays.map { it.id })
    }

    @Test
    fun `a resume before the load ends cancels the pause and a new choice forgets it`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        rig.music.pause()
        rig.music.resume()
        playing(rig, a)
        assertEquals(listOf(a.id), rig.plays.map { it.id })

        rig.music.next()
        rig.music.pause()
        rig.music.setQueue(listOf(c))
        playing(rig, c)
        assertEquals(listOf(a.id, c.id), rig.plays.map { it.id })
    }

    @Test
    fun `a pause while loading inside a talk still holds after the talk`() = runTest {
        val rig = Rig(this)
        rig.music.onTalkOpen(duck = false)
        rig.music.setQueue(listOf(a))
        runCurrent()
        rig.music.pause()
        rig.music.onTalkClose(1_000)
        playing(rig, a)
        assertTrue(rig.plays.isEmpty())
        assertEquals(false, rig.sync.anchor?.playing)
    }

    // ---- R5: a track that will not load ----

    @Test
    fun `a failed load skips to the next cached track`() = runTest {
        val rig = Rig(this)
        rig.store.files[c.id] = File("c.m4a")
        rig.music.setQueue(listOf(a, b, c))
        runCurrent()
        rig.store.fail(a.id)
        runCurrent()
        assertEquals(c.id, rig.music.current?.id)
        assertEquals(listOf("Couldn't load A. Skipping"), rig.errors)
        rig.music.onClientReady(c.id)
        runCurrent()
        assertEquals(listOf(c.id), rig.plays.map { it.id })
    }

    @Test
    fun `failed loads try the next tracks and give up after three, keeping the queue`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b, c, d))
        runCurrent()
        for (t in listOf(a, b, c)) {
            rig.store.fail(t.id)
            runCurrent()
        }
        assertEquals(c.id, rig.music.current?.id)
        assertEquals(listOf(d.id), rig.music.upcoming.map { it.id })
        assertNull(rig.music.phase)
        assertEquals("Couldn't load C", rig.errors.last())
        assertTrue(rig.music.canResume)

        // Not dead: a resume tries the track again.
        rig.music.resume()
        runCurrent()
        playing(rig, c)
        assertEquals(listOf(c.id), rig.plays.map { it.id })
    }

    @Test
    fun `a pause is kept across a skip`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        rig.music.pause()
        rig.store.fail(a.id)
        runCurrent()
        playing(rig, b)
        assertEquals(b.id, rig.music.current?.id)
        assertTrue(rig.plays.isEmpty())
    }

    @Test
    fun `with no network the track is kept and loads when the network is back`() = runTest {
        val rig = Rig(this)
        rig.online = false
        rig.music.setQueue(listOf(a, b))
        runCurrent()
        rig.store.fail(a.id)
        runCurrent()
        assertEquals(a.id, rig.music.current?.id)
        assertEquals(MusicPhase.LOADING, rig.music.phase)
        assertEquals(listOf("No network. A plays when it is back"), rig.errors)

        // No callback: it tries by itself every 30 s, and says it only once.
        rig.store.fail(a.id)
        advanceTimeBy(MusicController.OFFLINE_RETRY_MS + 1)
        assertEquals(listOf(a.id, a.id), rig.store.ensured)
        assertEquals(1, rig.errors.size)

        rig.online = true
        rig.music.onNetworkBack()
        playing(rig, a)
        assertEquals(listOf(a.id), rig.plays.map { it.id })
    }

    // ---- R4: ExoPlayer errors ----

    @Test
    fun `a player error reloads the track on the same anchor, a second one skips it`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        playing(rig, a)
        val anchor = rig.sync.anchor

        rig.player.loadedId = null
        rig.music.onPlayerError("ERROR_CODE_AUDIO_TRACK_INIT_FAILED")
        runCurrent()
        assertEquals(listOf(a.id, a.id), rig.player.loads)
        assertEquals(anchor, rig.sync.anchor)
        assertEquals("the client is not disturbed", 1, rig.plays.size)

        rig.player.loadedId = null
        rig.music.onPlayerError("ERROR_CODE_AUDIO_TRACK_INIT_FAILED")
        assertEquals(b.id, rig.music.current?.id)
        assertEquals(listOf("Couldn't play A. Skipping"), rig.errors)
        playing(rig, b)
        assertEquals(listOf(a.id, b.id), rig.plays.map { it.id })
    }

    @Test
    fun `a second player error on the last track pauses both phones and resume reloads it`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        repeat(2) {
            rig.player.loadedId = null
            rig.music.onPlayerError("boom")
        }
        assertEquals(listOf("Couldn't play A"), rig.errors)
        assertFalse(rig.music.isPlaying)
        assertEquals(1, rig.sent.filterIsInstance<com.kivan.motoparty.core.MusicPause>().size)

        rig.player.loadedId = null
        rig.music.resume()
        assertEquals(a.id, rig.player.loadedId)
        assertEquals(2, rig.plays.size)
    }

    @Test
    fun `a player error on a paused track leaves it paused`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        playing(rig, a)
        rig.music.pause()
        rig.player.loadedId = null
        rig.music.onPlayerError("boom")
        runCurrent()
        assertEquals(a.id, rig.player.loadedId)
        assertFalse(rig.music.isPlaying)
        assertEquals(1, rig.plays.size)
    }

    // ---- U-D2: the earbuds dropped ----

    @Test
    fun `becoming noisy pauses the music for both phones`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        advanceTimeBy(5_000)
        rig.music.onBecomingNoisy()
        assertFalse(rig.music.isPlaying)
        assertEquals(1, rig.sent.filterIsInstance<com.kivan.motoparty.core.MusicPause>().size)
        // Nothing to pause: nothing is sent.
        rig.music.onBecomingNoisy()
        assertEquals(1, rig.sent.filterIsInstance<com.kivan.motoparty.core.MusicPause>().size)
    }

    @Test
    fun `becoming noisy during a talk or right after it does not touch the resume`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        rig.music.onTalkOpen(duck = false)
        rig.music.onBecomingNoisy()
        assertTrue(rig.music.pausedForTalk)
        rig.music.onTalkClose(1_000)
        assertTrue(rig.music.isPlaying)
        advanceTimeBy(MusicController.NOISY_GUARD_MS - 1)
        rig.music.onBecomingNoisy()
        assertTrue(rig.music.isPlaying)
        assertTrue(rig.sent.none { it is com.kivan.motoparty.core.MusicPause })

        advanceTimeBy(1)
        rig.music.onBecomingNoisy()
        assertFalse(rig.music.isPlaying)
    }

    // ---- M1: the host is audible at the anchor ----

    @Test
    fun `a start and a resume are anchored far enough ahead for the host to start at the anchor`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        val lead = rig.sync.startLeadMs()
        assertTrue("$lead", lead >= MusicController.START_LEAD_MS)
        assertEquals(MusicPlay(a.id, 0, currentTime + lead), rig.plays.single())
        assertEquals("the host seeks to the anchor position, not past it", 0L, rig.player.seeks.last())
        advanceTimeBy(lead)
        assertTrue(rig.player.isPlaying)

        advanceTimeBy(5_000)
        rig.music.pause()
        rig.music.resume()
        runCurrent()
        assertEquals(MusicPlay(a.id, 5_000, currentTime + lead), rig.plays.last())
        assertEquals(5_000L, rig.player.seeks.last())
    }

    @Test
    fun `the resume after a talk keeps its own lead when that is the larger one`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        advanceTimeBy(3_000)
        rig.music.onTalkOpen(duck = false)
        rig.music.onTalkClose(resumeLeadMs = 1_500)
        assertEquals(currentTime + 1_500, rig.plays.last().atHostTimeMs)

        rig.music.onTalkOpen(duck = false)
        rig.music.onTalkClose(resumeLeadMs = 100)
        runCurrent()
        assertEquals(currentTime + rig.sync.startLeadMs(cold = true), rig.plays.last().atHostTimeMs)
        assertEquals(rig.plays.last().positionMs, rig.player.seeks.last())
    }

    // ---- M7: the end of the queue ----

    @Test
    fun `the end of the queue parks the last track paused at its start`() = runTest {
        val rig = Rig(this)
        rig.store.files[b.id] = File("b.m4a")
        rig.music.setQueue(listOf(a, b), start = 1)
        playing(rig, b)
        advanceTimeBy(200_000)
        assertFalse(rig.music.hasNext)
        rig.music.onTrackEnded()

        assertEquals(listOf(a, b), rig.music.queue)
        assertEquals(b, rig.music.current)
        assertEquals(Anchor(b.id, 0, currentTime, playing = false), rig.sync.anchor)
        assertEquals(listOf(MusicPause(b.id, 0)), rig.pauses)
        assertTrue(rig.sent.none { it is com.kivan.motoparty.core.MusicStop })
        assertEquals(false, rig.states.last()?.playing)
        assertEquals(0L, rig.player.seeks.last())

        rig.music.resume()
        assertEquals(MusicPlay(b.id, 0, currentTime + rig.sync.startLeadMs()), rig.plays.last())
        rig.music.next() // on the last track: parked again
        assertEquals(2, rig.pauses.size)
        rig.music.previous()
        playing(rig, a)
        assertEquals(a.id, rig.plays.last().id)
    }

    // ---- M5: gapless ----

    /** A plays, B is cached and the client is ready for it: B is queued and announced. */
    private fun kotlinx.coroutines.test.TestScope.armed(rig: Rig, queue: List<Track> = listOf(a, b, c)): MusicNext {
        rig.music.setQueue(queue)
        playing(rig, a)
        rig.store.finish(b.id)
        runCurrent()
        assertTrue("not before the client is ready: ${rig.sent}", rig.nexts.isEmpty())
        assertNull(rig.player.queued)
        rig.music.onClientReady(b.id)
        return rig.nexts.single()
    }

    @Test
    fun `the next track is queued and announced for the end of the current one`() = runTest {
        val rig = Rig(this)
        val next = armed(rig)
        assertEquals(MusicNext(b.id, rig.plays.single().atHostTimeMs + a.durationMs), next)
        assertEquals(b.id, rig.player.queued)
        // Nothing new: nothing is sent again.
        rig.music.onClientReady(b.id)
        assertEquals(1, rig.nexts.size)
    }

    @Test
    fun `the announced time uses the player's real duration and the anchor's position`() = runTest {
        val rig = Rig(this)
        rig.player.durationMs = 187_340
        armed(rig)
        advanceTimeBy(60_000)
        rig.music.pause()
        assertNull("a pause takes it back", rig.player.queued)
        val pausedAt = rig.pauses.single().positionMs
        rig.music.resume()
        val play = rig.plays.last()
        assertEquals(listOf(b.id, b.id), rig.nexts.map { it.id })
        assertEquals(play.atHostTimeMs + 187_340 - pausedAt, rig.nexts.last().atHostTimeMs)
        assertEquals(b.id, rig.player.queued)
    }

    @Test
    fun `at the change the queue moves on without a load, on the announced anchor`() = runTest {
        val rig = Rig(this)
        val next = armed(rig)
        advanceTimeBy(next.atHostTimeMs - currentTime + 20) // the player is 20 ms late
        val before = rig.sent.size
        val seeks = rig.player.seeks.size
        rig.player.advance()
        runCurrent()

        assertEquals(b, rig.music.current)
        assertEquals(Anchor(b.id, 0, next.atHostTimeMs, playing = true), rig.sync.anchor)
        assertEquals("no load, no seek, no pause", listOf(a.id), rig.player.loads)
        assertEquals(seeks, rig.player.seeks.size)
        assertTrue(rig.player.isPlaying)
        assertEquals(b.id, rig.states.last()?.id)
        assertEquals(next.atHostTimeMs, rig.states.last()?.atHostTimeMs)
        assertEquals(MusicPlay(b.id, 0, next.atHostTimeMs), rig.sent[before])
        assertEquals(listOf(a.id, b.id), rig.started)
        assertTrue(rig.log.any { it == "gapless: now ${b.id}, 20 ms after its anchor" })

        // The track after it: fetched, loaded on the client, then announced in its turn.
        rig.store.finish(c.id)
        runCurrent()
        assertEquals(listOf(a.id, b.id, c.id), rig.loads.map { it.id })
        rig.music.onClientReady(c.id)
        assertEquals(MusicNext(c.id, next.atHostTimeMs + b.durationMs), rig.nexts.last())
    }

    @Test
    fun `with no client the next track is queued as soon as it is cached`() = runTest {
        val rig = Rig(this)
        rig.client = false
        rig.music.setQueue(listOf(a, b))
        rig.store.finish(a.id)
        runCurrent()
        rig.store.finish(b.id)
        runCurrent()
        assertEquals(b.id, rig.player.queued)
        rig.player.advance()
        assertEquals(b, rig.music.current)
        assertTrue(rig.music.isPlaying)
    }

    @Test
    fun `a talk takes the queued track back and the resume announces it again`() = runTest {
        val rig = Rig(this)
        armed(rig)
        advanceTimeBy(10_000)
        rig.music.onTalkOpen(duck = false)
        assertNull(rig.player.queued)
        rig.music.onTalkClose(resumeLeadMs = 1_500)
        assertEquals(b.id, rig.player.queued)
        val play = rig.plays.last()
        assertEquals(MusicNext(b.id, play.atHostTimeMs + a.durationMs - play.positionMs), rig.nexts.last())

        // A ducked talk leaves the anchor alone, but the client dropped its pending next.
        rig.music.onTalkOpen(duck = true)
        assertNull(rig.player.queued)
        rig.music.onTalkClose(resumeLeadMs = 1_500)
        assertEquals(b.id, rig.player.queued)
        assertEquals(3, rig.nexts.size)
        assertEquals(rig.nexts[1], rig.nexts[2])
    }

    @Test
    fun `a queue edit replaces or cancels the queued track`() = runTest {
        val rig = Rig(this)
        rig.store.files[c.id] = File("c.m4a")
        armed(rig)
        rig.music.onClientReady(c.id)
        assertEquals(1, rig.nexts.size)
        // B removed: C is next and ready, so it is announced instead (that cancels B on the client).
        assertTrue(rig.music.remove(0, b.id))
        assertEquals(c.id, rig.player.queued)
        assertEquals(listOf(b.id, c.id), rig.nexts.map { it.id })
        assertEquals(rig.nexts[0].atHostTimeMs, rig.nexts[1].atHostTimeMs)
        assertEquals(1, rig.plays.size)

        // Nothing upcoming: the current track's music.play again, unchanged, cancels it.
        rig.music.clearUpcoming()
        assertNull(rig.player.queued)
        assertEquals(listOf(rig.plays[0], rig.plays[0]), rig.plays)
    }

    @Test
    fun `a skip takes the queued track back, and a track that is not ready changes the ordinary way`() = runTest {
        val rig = Rig(this)
        armed(rig)
        rig.music.jump(1, c.id)
        assertNull(rig.player.queued)

        val plain = Rig(this)
        plain.music.setQueue(listOf(a, b))
        playing(plain, a)
        plain.store.finish(b.id)
        runCurrent()
        advanceTimeBy(200_000)
        assertTrue("the client never said ready for B", plain.nexts.isEmpty())
        plain.music.onTrackEnded()
        runCurrent()
        assertEquals(listOf(a.id, b.id), plain.player.loads)
        plain.music.onClientReady(b.id)
        runCurrent()
        assertEquals(listOf(a.id, b.id), plain.plays.map { it.id })
    }

    @Test
    fun `too close to the end nothing is announced any more`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        playing(rig, a)
        rig.store.finish(b.id)
        advanceTimeBy(rig.plays.single().atHostTimeMs + a.durationMs - MusicController.GAPLESS_MIN_NOTICE_MS + 1 - currentTime)
        rig.music.onClientReady(b.id)
        assertTrue(rig.nexts.isEmpty())
        assertNull(rig.player.queued)
    }

    @Test
    fun `a player error drops the queued track and the reload announces it again`() = runTest {
        val rig = Rig(this)
        armed(rig)
        rig.player.loadedId = null
        rig.player.queued = null
        rig.music.onPlayerError("boom")
        runCurrent()
        assertEquals(listOf(a.id, a.id), rig.player.loads)
        assertEquals(b.id, rig.player.queued)
        assertEquals(2, rig.nexts.size)
    }

    // ---- voice actions (2026-10-01): repeat, move, seek, jump back ----

    @Test
    fun `repeat track starts the track again at its end and is never announced gapless`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        playing(rig, a)
        rig.store.finish(b.id)
        runCurrent()
        rig.music.onClientReady(b.id)
        assertEquals(b.id, rig.player.queued)
        rig.music.setRepeat(RepeatMode.TRACK)
        assertNull("taken back", rig.player.queued)
        assertEquals("track", rig.music.musicState()?.repeat)
        advanceTimeBy(200_000)
        rig.music.onTrackEnded()
        runCurrent()
        assertEquals(a, rig.music.current)
        assertEquals(MusicPlay(a.id, 0, rig.plays.last().atHostTimeMs), rig.plays.last())
        assertTrue(rig.plays.last().atHostTimeMs > 200_000)
        assertNull(rig.player.queued)
        // A `next` still goes on.
        rig.music.next()
        assertEquals(b, rig.music.current)
    }

    @Test
    fun `repeat queue goes back to the first track instead of parking the last`() = runTest {
        val rig = Rig(this)
        rig.store.files[a.id] = File("a.m4a")
        rig.music.setQueue(listOf(a, b), start = 1)
        playing(rig, b)
        rig.music.setRepeat(RepeatMode.QUEUE)
        assertFalse(rig.music.atEnd)
        assertEquals("queue", rig.states.last()?.repeat)
        advanceTimeBy(200_000)
        rig.music.onTrackEnded()
        runCurrent()
        assertEquals(a, rig.music.current)
        assertEquals(listOf(a, b), rig.music.queue)
        assertTrue(rig.pauses.isEmpty())
        // `next` on the last track wraps too.
        rig.music.next()
        rig.music.next()
        assertEquals(a, rig.music.current)
        rig.music.setRepeat(RepeatMode.OFF)
        assertNull(rig.music.musicState()?.repeat)
    }

    /** The client's `music.control repeat` (LinkHost maps its `mode` with [RepeatMode.of]). */
    @Test
    fun `a touch repeat from the client sets the mode with one state push, and the same mode none`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        playing(rig, a)
        for ((word, wire) in listOf("queue" to "queue", "track" to "track", "off" to null)) {
            val pushes = rig.states.size
            rig.music.setRepeat(RepeatMode.of(word)!!)
            assertEquals(pushes + 1, rig.states.size)
            assertEquals(wire, rig.states.last()?.repeat)
            rig.music.setRepeat(RepeatMode.of(word)!!)
            assertEquals("$word again changes nothing", pushes + 1, rig.states.size)
        }
    }

    @Test
    fun `move puts a track at its new upcoming index, and a stale one is refused`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b, c, d, e))
        playing(rig, a)
        assertTrue(rig.music.move(3, e.id, 0))
        assertEquals(listOf(e, b, c, d), rig.music.upcoming)
        assertFalse(rig.music.move(3, e.id, 0))
        assertTrue(rig.music.move(0, e.id, 99))
        assertEquals(listOf(b, c, d, e), rig.music.upcoming)
    }

    @Test
    fun `replacing the upcoming list keeps the current track playing`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b, c))
        playing(rig, a)
        val plays = rig.plays.size
        rig.music.replaceUpcoming(listOf(d, b))
        assertEquals(listOf(a, d, b), rig.music.queue)
        assertEquals(a, rig.music.current)
        assertEquals(plays, rig.plays.size)
    }

    @Test
    fun `seek while playing starts there, clamped to the track`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        assertTrue(rig.music.seek(90_000))
        assertEquals(90_000L, rig.plays.last().positionMs)
        rig.music.seek(-5_000)
        assertEquals(0L, rig.plays.last().positionMs)
        rig.music.seek(10_000_000)
        assertEquals(a.durationMs - MusicController.SEEK_END_MARGIN_MS, rig.plays.last().positionMs)
    }

    @Test
    fun `seek in a talk moves the anchor and the close resumes from there`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        playing(rig, a)
        rig.music.onTalkOpen(duck = false)
        val plays = rig.plays.size
        assertTrue(rig.music.seek(60_000))
        assertEquals("nothing plays in the talk", plays, rig.plays.size)
        assertEquals(60_000L, rig.music.positionMs)
        rig.music.onTalkClose(resumeLeadMs = 1_500)
        assertEquals(60_000L, rig.plays.last().positionMs)
    }

    @Test
    fun `jump back puts a played track right after the current one and plays it`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a, b))
        playing(rig, a)
        rig.music.jumpBack(c)
        assertEquals(listOf(a, c, b), rig.music.queue)
        assertEquals(c, rig.music.current)
    }
}
