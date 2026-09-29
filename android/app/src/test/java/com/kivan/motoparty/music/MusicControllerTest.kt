package com.kivan.motoparty.music

import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicLoad
import com.kivan.motoparty.core.MusicPlay
import com.kivan.motoparty.core.MusicState
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
        override fun seekTo(positionMs: Long) = Unit
        override fun load(track: Track, file: File) { loadedId = track.id }
        override fun stop() { loadedId = null }
    }

    /** A cache whose downloads finish only when the test says so. */
    private class FakeStore : TrackStore {
        override var opus = true
        val files = HashMap<String, File>()
        val downloads = HashMap<String, CompletableDeferred<Unit>>()
        override suspend fun ensure(id: String): File {
            files[id]?.let { return it }
            downloads.getOrPut(id) { CompletableDeferred() }.await()
            return File("$id.m4a").also { files[id] = it }
        }
        override fun cached(id: String): File? = files[id]
        fun finish(id: String) = downloads.getOrPut(id) { CompletableDeferred() }.complete(Unit)
    }

    private class Rig(scope: TestScope) {
        val store = FakeStore()
        val sent = mutableListOf<Message>()
        /** `state.music` at every push, as the client would see it. */
        val states = mutableListOf<MusicState?>()
        val log = mutableListOf<String>()
        val started = mutableListOf<String>()
        val player = FakePlayer()
        val sync = SyncController(player, scope.backgroundScope, hostNow = { scope.currentTime }, trimMs = { 0 })
            .apply { traceLog = null; log = {} }
        lateinit var music: MusicController
        init {
            music = MusicController(
                scope.backgroundScope, store, sync, player, hostNow = { scope.currentTime },
                send = { sent += it }, hasClient = { true },
                onChanged = { states += music.musicState() },
                onError = {}, log = { log += it },
                onStarted = { started += it.id },
            )
        }
        val plays get() = sent.filterIsInstance<MusicPlay>()
        val loads get() = sent.filterIsInstance<MusicLoad>()
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

    @Test
    fun `an error about the loaded track still lets the host play alone`() = runTest {
        val rig = Rig(this)
        rig.music.setQueue(listOf(a))
        rig.store.finish(a.id)
        runCurrent()
        rig.music.onClientError(a.id, "HTTP 500")
        runCurrent()
        assertEquals(listOf(a.id), rig.plays.map { it.id })
        assertEquals("music.error for ${a.id}: HTTP 500; playing without the client", rig.log.last())
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
        advanceTimeBy(5_300)
        rig.music.pause()
        assertEquals("pause at 5000 ms", rig.log.last())
        rig.music.resume()
        assertEquals("resume from 5000 ms", rig.log.last())
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
}
