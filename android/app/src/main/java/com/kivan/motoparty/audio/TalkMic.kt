package com.kivan.motoparty.audio

import android.media.AudioDeviceInfo

/**
 * Which microphone a talk uses, decided once at the open and fixed until that talk closes
 * (PROTOCOL.md "Host-mic talk", 2026-09-29). Pure: no Android call, no clock — the host hands in
 * the roster [DeviceWatch] already holds and the two settings.
 *
 * - [Lark]: a two-channel USB receiver (Hollyland Lark A1 RX in Stereo mode) is plugged in. The
 *   host captures both riders from it — rider on the left, passenger on the right — and the talk
 *   goes out as `talk.open{…, mic:"host"}`. No call mode anywhere.
 * - [Earbuds]: every phone uses its own headset mic, the call route as before (SCO, F8/F9). The
 *   [Earbuds.reason] is what the log line says.
 */
sealed interface TalkMic {
    /** Capture from the USB input [id] (what `AudioRecord.setPreferredDevice` is pointed at). */
    data class Lark(val id: Int, val type: Int, val name: String) : TalkMic

    data class Earbuds(val reason: String) : TalkMic

    companion object {
        /**
         * The talk microphone for a talk opening now. [devices]: the roster ([DeviceRoster.current]),
         * null before the first report. [enabled]: the "Use USB stereo mic (Lark) for talk" setting.
         *
         * A USB input qualifies when it can deliver two channels: its channel counts include 2, or
         * are empty, which the framework uses for "any". A mono one (a USB headset's boom mic) does
         * not — it cannot carry two riders. Several: a `usb_device` first (that is what the Lark
         * presents itself as), then `usb_headset`, then `usb_accessory`; ties by lowest id.
         */
        fun choose(devices: List<DeviceRoster.Dev>?, enabled: Boolean): TalkMic {
            if (!enabled) return Earbuds("setting off")
            if (devices == null) return Earbuds("no device list yet")
            val usb = devices.filter { it.isInput && it.type in USB_TYPES }
            if (usb.isEmpty()) return Earbuds("no USB input")
            val stereo = usb.filter { it.channelCounts.isEmpty() || 2 in it.channelCounts }
            val best = stereo.minWithOrNull(compareBy({ USB_TYPES.indexOf(it.type) }, { it.id }))
                ?: return Earbuds("USB input ${usb.joinToString { describe(it) }} is mono")
            return Lark(best.id, best.type, best.name)
        }

        /**
         * Why the Lark cannot be used right now although the setting asks for it — `no USB input`,
         * `USB input … is mono` — or null: the setting is off, the roster is not known yet, or a
         * receiver is there. The Ride tab's warning and the `lark receiver:` log line read this, so
         * a receiver that is plugged in but not enumerated (2026-09-29) cannot go unnoticed.
         */
        fun missing(devices: List<DeviceRoster.Dev>?, enabled: Boolean): String? {
            if (!enabled || devices == null) return null
            return (choose(devices, true) as? Earbuds)?.reason
        }

        /**
         * One line per talk open, for the device run:
         * `talk mic: lark usb_device#27 "Lark A1", swap off` or `talk mic: earbuds (no USB input)`.
         */
        fun line(mic: TalkMic, swap: Boolean): String = when (mic) {
            is Lark -> "talk mic: lark ${describe(mic.type, mic.id, mic.name)}, swap ${if (swap) "on" else "off"}"
            is Earbuds -> "talk mic: earbuds (${mic.reason})"
        }

        private fun describe(d: DeviceRoster.Dev) = describe(d.type, d.id, d.name)

        private fun describe(type: Int, id: Int, name: String) =
            "${ScoRule.describe(type)}#$id" + if (name.isBlank()) "" else " \"$name\""

        /** In order of preference. */
        private val USB_TYPES = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}

/**
 * Is the Lark recorder really on the receiver? Pure and clock-free (every call carries the time
 * since the recorder started). `setPreferredDevice` is a preference, not a promise: a recorder the
 * policy put somewhere else would carry the phone's own mic as "both riders", in silence. So the
 * capture loop asks this on every routing report and every ~500 ms.
 *
 * - routed to [expectedId]: [Verdict.OK] (and remembered);
 * - routed to any other device: [Verdict.WRONG] at once — the receiver was pulled out or refused;
 * - not reported yet (null): [Verdict.PENDING], until [graceMs] have passed without the receiver
 *   ever being confirmed, then [Verdict.WRONG]. A null after a confirmation stays PENDING: the
 *   framework clears the route briefly around its own reshuffles, and a real loss shows up as a
 *   device (or a read error) soon enough.
 */
class LarkRouteCheck(private val expectedId: Int, private val graceMs: Long = GRACE_MS) {
    enum class Verdict { OK, PENDING, WRONG }

    var confirmed = false
        private set

    fun check(routedId: Int?, sinceStartMs: Long): Verdict = when {
        routedId == expectedId -> {
            confirmed = true
            Verdict.OK
        }
        routedId != null -> Verdict.WRONG
        !confirmed && sinceStartMs >= graceMs -> Verdict.WRONG
        else -> Verdict.PENDING
    }

    companion object {
        /** The probe's recorder reported its USB route within the first read; this is generous. */
        const val GRACE_MS = 2_000L
    }
}
