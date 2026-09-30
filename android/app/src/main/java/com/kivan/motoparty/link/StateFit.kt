package com.kivan.motoparty.link

import com.kivan.motoparty.core.Codec
import com.kivan.motoparty.core.State

/**
 * PROTOCOL.md "Control channel", `state.queue`: the host leaves `art` out of *every* queue item
 * when the encoded `state` would pass [STATE_ART_LIMIT] (a long queue of cover URLs must never
 * near the 64 KiB frame limit). `durationMs` and the current track's own `art` stay.
 */
object StateFit {
    const val STATE_ART_LIMIT = 48 * 1024

    fun fit(state: State, limit: Int = STATE_ART_LIMIT): State {
        if (state.queue.none { it.art != null }) return state
        if (Codec.encode(state).toByteArray(Charsets.UTF_8).size <= limit) return state
        return state.copy(queue = state.queue.map { it.copy(art = null) })
    }
}
