package com.kivan.motoparty.audio

/**
 * What audio devices the phone has right now, and what changed since the last look. Pure: no
 * Android, no clock, no I/O — [DeviceWatch] does the asking and the logging.
 *
 * Stage A of the wired-mic plan (2026-09-20). Until now the app never asked: there was no
 * `AudioManager.getDevices()` and no `AudioDeviceCallback` anywhere, so the only device it could
 * name was the one its own `setCommunicationDevice()` had picked. The bench questions of Stage B
 * are all of the form "what does this dongle present itself as, and when" — which is this roster,
 * before and after the cable goes in.
 *
 * Device names are used in [ScoRule]'s spelling, which is the framework's own, so a roster line and
 * an `AS.AudioDeviceBroker` line can be read side by side; the id is carried because that is what
 * `AudioRecord.setPreferredDevice()` will be pointed at in Stage C.
 */
class DeviceRoster {
    /** One device, flattened out of `AudioDeviceInfo`: a type, a name and which way it points. */
    data class Dev(val id: Int, val type: Int, val name: String, val isInput: Boolean)

    /** Null until the first [update]; sorted, so an unchanged roster compares equal. */
    private var known: List<Dev>? = null

    /**
     * The devices the framework reports now. Returns the lines to log — empty when nothing changed,
     * which is the common case, since the framework re-reports on every route change too.
     *
     * A change logs what went and what came **and** the whole roster after it, so a bench run never
     * has to reconstruct the current state by replaying deltas.
     */
    fun update(devices: List<Dev>): List<String> {
        // Inputs first, then by type and id: the order the lines below read in, and stable, so an
        // unchanged roster compares equal whatever order the framework happened to answer in.
        val now = devices.sortedWith(compareBy({ !it.isInput }, { it.type }, { it.id }))
        val was = known
        if (was == now) return emptyList()
        known = now
        val full = "audio devices: ${roster(now)}"
        if (was == null) return listOf(full)
        val added = now.filterNot { it in was }
        val gone = was.filterNot { it in now }
        return buildList {
            if (added.isNotEmpty()) add("audio devices +: ${added.joinToString(", ") { one(it) }}")
            if (gone.isNotEmpty()) add("audio devices -: ${gone.joinToString(", ") { one(it) }}")
            add(full)
        }
    }

    /**
     * The UI row: types only, each one once, inputs first. Null before the first [update] — the row
     * then shows what it shows for everything else it does not know yet.
     */
    fun summary(): String? {
        val now = known ?: return null
        fun side(input: Boolean) = now.filter { it.isInput == input }
            .map { ScoRule.describe(it.type) }.distinct()
            .ifEmpty { listOf("none") }.joinToString(", ")
        return "in ${side(true)} · out ${side(false)}"
    }

    /** `in builtin_mic#15 "Pixel 8", bt_sco#5 "AirPods Pro" · out earpiece#1 "Pixel 8", …` */
    private fun roster(devices: List<Dev>): String {
        fun side(input: Boolean) = devices.filter { it.isInput == input }
            .map { short(it) }.ifEmpty { listOf("none") }.joinToString(", ")
        return "in ${side(true)} · out ${side(false)}"
    }

    private fun one(d: Dev): String = "${if (d.isInput) "in" else "out"} ${short(d)}"

    private fun short(d: Dev): String =
        "${ScoRule.describe(d.type)}#${d.id}" + if (d.name.isBlank()) "" else " \"${d.name}\""
}
