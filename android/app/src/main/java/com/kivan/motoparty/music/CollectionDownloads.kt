package com.kivan.motoparty.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** How far one album or playlist download got. [running] false = finished (or never started). */
data class DownloadProgress(val done: Int, val total: Int, val failed: Int = 0, val running: Boolean = true) {
    /** The Search tab's button text: glanceable, one line. */
    val label: String
        get() = when {
            running -> "Downloading $done/$total"
            failed == 0 -> "Downloaded"
            else -> "Retry · ${done - failed}/$total saved"
        }
}

/**
 * The Search tab's **Download** button: every track of an album or playlist into the track cache,
 * for riding through patchy coverage. One track at a time across every collection (a second
 * download waits its turn track by track), so it never takes more than one stream from the track
 * that is about to play. Already-cached tracks count as done at once. Cancelling stops after the
 * track in flight, which the cache finishes and keeps. Main thread; [ensure] does the I/O.
 */
class CollectionDownloads(
    private val scope: CoroutineScope,
    private val ensure: suspend (id: String) -> File,
    private val cached: (id: String) -> File?,
    /** Every change, with all collections' progress by collection id. */
    private val onProgress: (Map<String, DownloadProgress>) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val jobs = HashMap<String, Job>()
    private var progress: Map<String, DownloadProgress> = emptyMap()
    private val oneAtATime = Mutex()

    val current: Map<String, DownloadProgress> get() = progress

    /** Start downloading [ids] for collection [key]; a download of it already running is left alone. */
    fun start(key: String, ids: List<String>) {
        if (jobs[key]?.isActive == true || ids.isEmpty()) return
        val unique = ids.distinct()
        set(key, DownloadProgress(0, unique.size))
        log("download $key: ${unique.size} track(s)")
        // Lazy, so the map holds the job before it can run (and finish) inline on Main.immediate.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var done = 0
            var failed = 0
            for (id in unique) {
                if (cached(id) == null) {
                    try {
                        oneAtATime.withLock { ensure(id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        log("download $key: $id failed: $e")
                    }
                }
                done++
                set(key, DownloadProgress(done, unique.size, failed, running = done < unique.size))
            }
            log("download $key: done, $failed failed")
            jobs.remove(key)
        }
        jobs[key] = job
        job.start()
    }

    /** Stop collection [key]'s download and forget its progress (the button reads "Download" again). */
    fun cancel(key: String) {
        jobs.remove(key)?.cancel() ?: return
        log("download $key: cancelled")
        progress = progress - key
        onProgress(progress)
    }

    private fun set(key: String, p: DownloadProgress) {
        progress = progress + (key to p)
        onProgress(progress)
    }
}
