package com.kivan.motoparty.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDownloadsTest {
    /** A cache whose downloads finish (or fail) only when the test says so; records the order asked. */
    private class Store {
        val files = HashMap<String, File>()
        val asked = mutableListOf<String>()
        val pending = HashMap<String, CompletableDeferred<Boolean>>()
        suspend fun ensure(id: String): File {
            files[id]?.let { return it }
            asked += id
            val ok = pending.getOrPut(id) { CompletableDeferred() }.await()
            if (!ok) throw IOException("no coverage")
            return File(id).also { files[id] = it }
        }
        fun finish(id: String, ok: Boolean = true) = pending.getOrPut(id) { CompletableDeferred() }.complete(ok)
    }

    @Test
    fun oneTrackAtATimeWithProgressAndCachedOnesCountedAtOnce() = runTest {
        val store = Store().apply { files["b"] = File("b") }
        var last: Map<String, DownloadProgress> = emptyMap()
        val d = CollectionDownloads(backgroundScope, store::ensure, { store.files[it] }, { last = it })
        d.start("album", listOf("a", "b", "c"))
        runCurrent()
        assertEquals(DownloadProgress(0, 3), last["album"])
        assertEquals(listOf("a"), store.asked)
        store.finish("a")
        runCurrent()
        // "b" was cached already: done without a download, and "c" is next.
        assertEquals(DownloadProgress(2, 3), last["album"])
        assertEquals(listOf("a", "c"), store.asked)
        assertEquals("Downloading 2/3", last["album"]!!.label)
        store.finish("c")
        runCurrent()
        assertEquals(DownloadProgress(3, 3, running = false), last["album"])
        assertEquals("Downloaded", last["album"]!!.label)
    }

    @Test
    fun twoCollectionsShareOneStream() = runTest {
        val store = Store()
        val d = CollectionDownloads(backgroundScope, store::ensure, { store.files[it] }, {})
        d.start("x", listOf("x1", "x2"))
        d.start("y", listOf("y1"))
        runCurrent()
        assertEquals("only one download at a time", 1, store.asked.size)
        store.finish(store.asked.last())
        runCurrent()
        assertEquals(2, store.asked.size)
    }

    @Test
    fun failuresAreCountedAndOfferARetry() = runTest {
        val store = Store()
        var last: Map<String, DownloadProgress> = emptyMap()
        val d = CollectionDownloads(backgroundScope, store::ensure, { store.files[it] }, { last = it })
        d.start("p", listOf("a", "b"))
        runCurrent()
        store.finish("a", ok = false)
        runCurrent()
        store.finish("b")
        runCurrent()
        assertEquals(DownloadProgress(2, 2, failed = 1, running = false), last["p"])
        assertEquals("Retry · 1/2 saved", last["p"]!!.label)
    }

    @Test
    fun cancelStopsAndForgetsTheProgress() = runTest {
        val store = Store()
        var last: Map<String, DownloadProgress> = emptyMap()
        val d = CollectionDownloads(backgroundScope, store::ensure, { store.files[it] }, { last = it })
        d.start("p", listOf("a", "b"))
        runCurrent()
        d.cancel("p")
        store.finish("a")
        runCurrent()
        assertNull(last["p"])
        assertEquals("b is never asked for", listOf("a"), store.asked)
        // And it can be started again.
        d.start("p", listOf("a", "b"))
        runCurrent()
        assertEquals(DownloadProgress(1, 2), last["p"])
    }
}
