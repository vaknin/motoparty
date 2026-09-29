package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo

/**
 * When [VoiceEngine]'s capture loop must throw its `AudioRecord` away and open a new one (F9c,
 * 2026-09-29). Pure: fed the recorder's routed-device reports, no Android calls.
 *
 * Why. Device run 2026-09-29 (Pixel 8, AirPods Pro): every talk whose recorder was first routed to
 * the **built-in mic** and only later migrated to `bt_sco` stayed near-silent after the migration
 * (`peaks … 4 0 0 0 0 …`, `tx 91 sent of 2626 captured (2535 DTX)`), while the talk whose recorder
 * was routed **straight** to `bt_sco` sent every frame (`tx 471 sent of 471 captured (0 DTX)`). A
 * `VOICE_COMMUNICATION` recorder with AEC + NS that was set up on the built-in mic does not recover
 * on the SCO input; a fresh one opened while SCO is the input does.
 *
 * The rule: the **current** recorder reported some non-SCO input and now reports the SCO input →
 * re-open. A null report ("the framework has not said") is neither. At most [maxReopens] per
 * session, so a flapping link cannot turn the capture loop into an open/release loop. A recorder
 * that starts on SCO and later falls back to the built-in mic is left alone: that is the headset
 * going away, and a new recorder would land on the same built-in mic.
 */
class CaptureReopen(private val maxReopens: Int = MAX_REOPENS) {
    /** The last non-null input the current recorder reported, or null: nothing yet. */
    private var lastType: Int? = null

    /** Re-opens done this session. */
    var reopens = 0
        private set

    /** The recorder's input is [type] (null = unknown). True: re-open it now. */
    fun routed(type: Int?): Boolean {
        if (type == null) return false
        val was = lastType
        lastType = type
        return type == SCO && was != null && was != SCO && reopens < maxReopens
    }

    /** The recorder was replaced: its reports start over. */
    fun reopened() {
        reopens++
        lastType = null
    }

    companion object {
        const val MAX_REOPENS = 2
        private const val SCO = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
}
