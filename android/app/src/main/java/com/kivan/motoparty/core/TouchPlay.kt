package com.kivan.motoparty.core

/**
 * PROTOCOL.md "Browsing" step 3, *Play by touch ends a talk* (2026-09-30): which touches, on
 * either phone, start a track now and so end an open talk like a spoken `play`: a `now` enqueue,
 * and every `jump` edit. The rest (`next`, `end`, `remove`, `clear`) leave the talk open.
 */
object TouchPlay {
    fun enqueueEndsTalk(mode: String): Boolean = mode == EnqueueMode.NOW
}
