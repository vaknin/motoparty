package com.kivan.motoparty.link

import com.kivan.motoparty.core.Codec
import com.kivan.motoparty.core.DownloadItem
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicControl
import com.kivan.motoparty.core.MusicDownload
import com.kivan.motoparty.core.MusicDownloads
import com.kivan.motoparty.core.RepeatMode
import com.kivan.motoparty.music.CollectionDownloads
import com.kivan.motoparty.music.DownloadProgress
import com.kivan.motoparty.music.QueueEdits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * What LinkHost does with the client's `music.control repeat` and `music.download`, the
 * `music.downloads` it sends back (on hello, on progress, on a cache change, trimmed to a frame),
 * and `state.busy` around a voice command's searches. The pieces are the ones LinkHost wires
 * ([ClientMusicRequests], [DownloadsFeed], [BusyLine]) around the real [CollectionDownloads];
 * messages go in and out through [Codec], as on the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostMusicWiringTest {

    /** The active track cache: downloads finish (or fail) only when the test says so. */
    private class Cache {
        val files = LinkedHashMap<String, File>()
        val asked = mutableListOf<String>()
        private val pending = HashMap<String, CompletableDeferred<Boolean>>()
        /** LinkHost's TrackCache onChange -> refreshCached -> push. */
        var onChange: () -> Unit = {}
        suspend fun ensure(id: String): File {
            files[id]?.let { return it }
            asked += id
            if (!pending.getOrPut(id) { CompletableDeferred() }.await()) {
                pending.remove(id) // a retry downloads it again
                throw IOException("no coverage")
            }
            return File(id).also { files[id] = it; onChange() }
        }
        fun finish(id: String, ok: Boolean = true) = pending.getOrPut(id) { CompletableDeferred() }.complete(ok)
    }

    /** LinkHost's music + downloads wiring, with the client's side of the socket as [sent]. */
    private class Host(scope: TestScope) {
        val cache = Cache()
        val sent = mutableListOf<Message>()
        val log = mutableListOf<String>()
        val music = mutableListOf<String>()
        val repeats = mutableListOf<RepeatMode>()
        lateinit var feed: DownloadsFeed
        val downloads = CollectionDownloads(
            scope.backgroundScope, cache::ensure, { cache.files[it] },
            onProgress = { feed.push() },
        )
        val requests = ClientMusicRequests(
            pause = { music += "pause" }, resume = { music += "resume" },
            next = { music += "next" }, previous = { music += "previous" },
            setRepeat = { repeats += it },
            startDownload = downloads::start, cancelDownload = downloads::cancel,
            log = { log += it },
        )

        init {
            // Through the codec both ways, so only what survives the wire counts.
            feed = DownloadsFeed({ cache.files.keys }, { downloads.current }, send = { sent += Codec.decode(Codec.encode(it)) })
            cache.onChange = { feed.push() }
        }

        /** A frame from the client, as LinkHost.onMessage routes it. */
        fun receive(json: String) {
            when (val m = Codec.decode(json)) {
                is MusicControl -> requests.control(m.action, "client", m.mode)
                is MusicDownload -> requests.download(m)
                else -> error("not routed here: $m")
            }
        }

        /** LinkHost on ControlServer.Event.ClientConnected. */
        fun hello() = feed.push(force = true)

        val downloadsSent get() = sent.filterIsInstance<MusicDownloads>()
    }

    // ---- music.control repeat ----

    @Test
    fun `music control repeat sets each mode, off included`() = runTest {
        val host = Host(this)
        for (word in listOf("queue", "track", "off")) {
            host.receive("""{"t":"music.control","action":"repeat","mode":"$word"}""")
        }
        assertEquals(listOf(RepeatMode.QUEUE, RepeatMode.TRACK, RepeatMode.OFF), host.repeats)
        assertEquals("music control repeat track from client", host.log[1])
        assertTrue("nothing else moved", host.music.isEmpty())
    }

    @Test
    fun `the other control actions are not a repeat, and a repeat without a mode never arrives`() = runTest {
        val host = Host(this)
        for (action in listOf("pause", "resume", "next", "previous")) {
            host.receive("""{"t":"music.control","action":"$action"}""")
        }
        assertEquals(listOf("pause", "resume", "next", "previous"), host.music)
        assertTrue(host.repeats.isEmpty())
        // The codec drops these as malformed: LinkHost never sees them.
        for (bad in listOf("""{"t":"music.control","action":"repeat"}""", """{"t":"music.control","action":"repeat","mode":"all"}""")) {
            assertTrue(bad, runCatching { Codec.decode(bad) }.isFailure)
        }
        // And one that got through anyway (an outside caller) changes nothing.
        host.requests.control("repeat", "test", "all")
        assertTrue(host.repeats.isEmpty())
    }

    // ---- music.download / music.downloads ----

    @Test
    fun `hello always gets music downloads, later only changes are sent`() = runTest {
        val host = Host(this)
        host.cache.files["zz"] = File("zz")
        host.cache.files["aa"] = File("aa")
        host.hello()
        assertEquals(listOf(MusicDownloads(cached = listOf("aa", "zz"), downloads = emptyList())), host.sent)
        host.feed.push() // a cache change that changed nothing
        assertEquals(1, host.sent.size)
        host.hello() // a client that reconnects gets it again, unchanged or not
        assertEquals(2, host.sent.size)
        assertEquals(host.sent[0], host.sent[1])
    }

    @Test
    fun `a client download goes out on every progress step with the marks, and stop clears it`() = runTest {
        val host = Host(this)
        host.hello()
        host.receive("""{"t":"music.download","op":"start","ref":"PL1","ids":["a1","bad id","b2","a1"]}""")
        runCurrent()
        assertEquals("invalid and repeated ids dropped", listOf("a1"), host.cache.asked)
        assertEquals(
            MusicDownloads(cached = emptyList(), downloads = listOf(DownloadItem("PL1", 0, 2, 0, running = true))),
            host.downloadsSent.last(),
        )

        host.cache.finish("a1")
        runCurrent()
        // The cache change (the mark) and the progress step each send one.
        assertEquals(
            listOf(
                MusicDownloads(cached = emptyList(), downloads = emptyList()),
                MusicDownloads(cached = emptyList(), downloads = listOf(DownloadItem("PL1", 0, 2, 0, true))),
                MusicDownloads(cached = listOf("a1"), downloads = listOf(DownloadItem("PL1", 0, 2, 0, true))),
                MusicDownloads(cached = listOf("a1"), downloads = listOf(DownloadItem("PL1", 1, 2, 0, true))),
            ),
            host.downloadsSent,
        )

        host.cache.finish("b2", ok = false)
        runCurrent()
        assertEquals(DownloadItem("PL1", 2, 2, 1, running = false), host.downloadsSent.last().downloads.single())

        // Retry: the failed one is fetched again, the cached one is not; stop (while it is in
        // flight) forgets the progress.
        host.receive("""{"t":"music.download","op":"start","ref":"PL1","ids":["a1","b2"]}""")
        runCurrent()
        assertEquals(listOf("a1", "b2", "b2"), host.cache.asked)
        host.receive("""{"t":"music.download","op":"stop","ref":"PL1"}""")
        runCurrent()
        assertEquals(MusicDownloads(cached = listOf("a1"), downloads = emptyList()), host.downloadsSent.last())
        assertTrue(host.log.none { it.contains("ignored") || it.contains("no valid ids") })
    }

    @Test
    fun `a bad download request is logged and starts nothing`() = runTest {
        val host = Host(this)
        host.receive("""{"t":"music.download","op":"start","ref":"no/such ref","ids":["a1"]}""")
        host.receive("""{"t":"music.download","op":"start","ref":"PL1","ids":["bad id","${"x".repeat(65)}"]}""")
        runCurrent()
        assertEquals(listOf("client download: bad ref ignored", "client download PL1: no valid ids"), host.log)
        assertTrue(host.cache.asked.isEmpty())
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `a download is capped at the queue's length`() = runTest {
        val host = Host(this)
        val ids = (0 until QueueEdits.MAX_UPCOMING + 50).map { "id%08d".format(it) }
        host.receive(Codec.encode(MusicDownload(op = "start", ref = "PL1", ids = ids)))
        runCurrent()
        assertEquals(QueueEdits.MAX_UPCOMING, host.downloads.current.getValue("PL1").total)
    }

    @Test
    fun `music downloads is trimmed to one frame by dropping trailing cached ids`() = runTest {
        val host = Host(this)
        // ~6,000 ids of 11 characters: ~84 KB of JSON, more than the 64 KiB frame.
        val ids = (0 until 6_000).map { "c%010d".format(it) }
        ids.shuffled(java.util.Random(1)).forEach { host.cache.files[it] = File(it) }
        host.receive("""{"t":"music.download","op":"start","ref":"PL1","ids":["n1"]}""")
        runCurrent()
        val m = host.downloadsSent.last()
        assertTrue("fits a frame", Codec.fits(m))
        assertTrue("trimmed", m.cached.size in 1 until ids.size)
        assertEquals("the smallest ids are kept, in order", ids.take(m.cached.size), m.cached)
        assertEquals("downloads are never trimmed", listOf(DownloadItem("PL1", 0, 1, 0, true)), m.downloads)
        assertEquals(m, DownloadsFeed.message(ids, host.downloads.current))
        // An unchanged trimmed message is not sent again.
        val count = host.sent.size
        host.feed.push()
        assertEquals(count, host.sent.size)
    }

    // ---- state.busy ----

    @Test
    fun `busy is up while the searches run and cleared once all are done, failed ones too`() = runTest {
        val changes = mutableListOf<String?>()
        val busy = BusyLine { changes += it }
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        // LinkHost's searches are async jobs in its supervisor scope: a failed one fails only itself.
        val searches = listOf(first, second)
        val run = launch { busy.whileSearching("Searching Moby, Coldplay", searches) }
        runCurrent()
        assertEquals("Searching Moby, Coldplay", busy.text)
        first.complete(Unit)
        runCurrent()
        assertEquals("one search still runs", "Searching Moby, Coldplay", busy.text)
        second.completeExceptionally(IOException("no coverage"))
        runCurrent()
        assertNull(busy.text)
        assertTrue(run.isCompleted)
        assertEquals("one push up, one down", listOf("Searching Moby, Coldplay", null), changes)
    }

    @Test
    fun `busy is cleared when the command is cancelled, and never shown without a search`() = runTest {
        val changes = mutableListOf<String?>()
        val busy = BusyLine { changes += it }
        busy.whileSearching("Searching nothing", emptyList())
        assertTrue(changes.isEmpty())

        val never = CompletableDeferred<Unit>()
        val run = launch { busy.whileSearching("Searching Moby", listOf(never)) }
        runCurrent()
        assertEquals("Searching Moby", busy.text)
        run.cancel()
        runCurrent()
        assertNull(busy.text)
        busy.set(null)
        assertEquals("an unchanged value pushes nothing", listOf("Searching Moby", null), changes)
    }
}
