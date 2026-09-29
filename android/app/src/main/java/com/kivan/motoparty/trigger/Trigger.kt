package com.kivan.motoparty.trigger

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * TALK toggles the intercom. The only kind since option A (2026-09-29): commands are spoken
 * inside a talk, so there is no separate command trigger any more.
 */
enum class TriggerKind { TALK }

enum class TriggerSource { OVERLAY, UI }

data class Trigger(val kind: TriggerKind, val source: TriggerSource)

/** The one stream every trigger source feeds; [com.kivan.motoparty.LinkService] consumes it. */
object Triggers {
    private val _events = MutableSharedFlow<Trigger>(extraBufferCapacity = 16)
    val events: SharedFlow<Trigger> = _events

    fun fire(kind: TriggerKind, source: TriggerSource) {
        _events.tryEmit(Trigger(kind, source))
    }
}
