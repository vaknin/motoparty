package com.kivan.motoparty.link

import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.core.DownloadOp
import com.kivan.motoparty.core.MusicDownload
import com.kivan.motoparty.core.RepeatMode
import com.kivan.motoparty.music.QueueEdits
import com.kivan.motoparty.music.isValidTrackId

/**
 * The host's side of `music.control` (the client's and an outside controller's) and the client's
 * `music.download`: which music or download call each one becomes. Main.
 */
class ClientMusicRequests(
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val next: () -> Unit,
    private val previous: () -> Unit,
    private val setRepeat: (RepeatMode) -> Unit,
    private val startDownload: (ref: String, ids: List<String>) -> Unit,
    private val cancelDownload: (ref: String) -> Unit,
    private val log: (String) -> Unit,
) {
    /**
     * [mode] is the `repeat` action's mode (PROTOCOL.md "Repeat by touch"): set like our own
     * repeat button, so it leaves the voice undo alone.
     */
    fun control(action: String, from: String, mode: String? = null) {
        log("music control $action${mode?.let { " $it" } ?: ""} from $from")
        // Values outside this set (and a repeat without a mode) never get here: the codec drops
        // them as malformed.
        when (action) {
            ControlAction.PAUSE -> pause()
            ControlAction.RESUME -> resume()
            ControlAction.NEXT -> next()
            ControlAction.PREVIOUS -> previous()
            ControlAction.REPEAT -> RepeatMode.of(mode)?.let(setRepeat)
        }
    }

    /**
     * PROTOCOL.md "Browsing" step 6: the client's Download button, through the same collection
     * downloads as ours, keyed by the collection's ref. Progress and the marks go back in
     * `music.downloads` ([DownloadsFeed]).
     */
    fun download(m: MusicDownload) {
        if (!isValidTrackId(m.ref)) return log("client download: bad ref ignored")
        when (m.op) {
            DownloadOp.STOP -> cancelDownload(m.ref)
            DownloadOp.START -> {
                val ids = m.ids.orEmpty().filter(::isValidTrackId).distinct().take(QueueEdits.MAX_UPCOMING)
                if (ids.isEmpty()) log("client download ${m.ref}: no valid ids") else startDownload(m.ref, ids)
            }
        }
    }
}
