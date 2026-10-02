package com.kivan.motoparty.link

import com.kivan.motoparty.core.Codec
import com.kivan.motoparty.core.DownloadItem
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.MusicDownloads
import com.kivan.motoparty.music.DownloadProgress

/**
 * PROTOCOL.md "Browsing" step 6 on the host: `music.downloads`, the cached ids and every
 * collection's progress, sent to the client when either changed, and always to a client that
 * just said `hello` ([push] with `force`). Trimmed to one frame by [Codec.fit]. Main.
 */
class DownloadsFeed(
    private val cached: () -> Collection<String>,
    private val downloads: () -> Map<String, DownloadProgress>,
    private val send: (Message) -> Unit,
) {
    /** The last one sent, so an unchanged one is not sent again. */
    private var last: MusicDownloads? = null

    fun push(force: Boolean = false) {
        val m = message(cached(), downloads())
        if (!force && m == last) return
        last = m
        send(m)
    }

    companion object {
        fun message(cached: Collection<String>, downloads: Map<String, DownloadProgress>): MusicDownloads = Codec.fit(
            MusicDownloads(
                cached = cached.sorted(),
                downloads = downloads.map { (ref, p) -> DownloadItem(ref, p.done, p.total, p.failed, p.running) },
            ),
        )
    }
}
