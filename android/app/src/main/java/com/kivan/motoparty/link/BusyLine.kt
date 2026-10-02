package com.kivan.motoparty.link

import kotlinx.coroutines.Job

/**
 * `state.busy`, the status line's "Searching …" on both screens (PROTOCOL.md `state`), while a
 * voice command's searches run. [changed] gets every change (LinkHost pushes `state` from it).
 * Main.
 */
class BusyLine(private val changed: (String?) -> Unit) {
    var text: String? = null
        private set

    fun set(text: String?) {
        if (text == this.text) return
        this.text = text
        changed(text)
    }

    /**
     * Shows [text] until every one of [searches] is done, failed or cancelled, and clears it then,
     * also when the caller is cancelled. With no searches nothing is shown or cleared.
     */
    suspend fun whileSearching(text: String, searches: Collection<Job>) {
        if (searches.isEmpty()) return
        set(text)
        try {
            searches.forEach { it.join() }
        } finally {
            set(null)
        }
    }
}
