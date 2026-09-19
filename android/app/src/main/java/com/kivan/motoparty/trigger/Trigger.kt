package com.kivan.motoparty.trigger

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** TALK toggles the intercom; MUSIC opens the mic for a voice command. */
enum class TriggerKind { TALK, MUSIC }

enum class TriggerSource { OVERLAY, MEDIA_BUTTON, UI }

data class Trigger(val kind: TriggerKind, val source: TriggerSource)

/** The one stream every trigger source feeds; [com.kivan.motoparty.LinkService] consumes it. */
object Triggers {
    private val _events = MutableSharedFlow<Trigger>(extraBufferCapacity = 16)
    val events: SharedFlow<Trigger> = _events

    fun fire(kind: TriggerKind, source: TriggerSource) {
        _events.tryEmit(Trigger(kind, source))
    }
}
