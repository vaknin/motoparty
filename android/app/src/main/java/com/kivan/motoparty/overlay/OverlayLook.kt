package com.kivan.motoparty.overlay

/** `Palette.Talk`. */
private const val TALK = 0xFFFF7A2F.toInt()
/** `Palette.TalkOpen`. */
private const val TALK_OPEN = 0xFFE5484D.toInt()
/** The theme's `onPrimary`. */
private const val ON_TALK = 0xFF1F1004.toInt()
private const val ON_TALK_OPEN = 0xFFFFFFFF.toInt()

/**
 * What the floating button shows (UA2). Pure, so the words and colours are pinned by a test: the
 * button, the Ride tab's TALK button and the notification action must read alike — orange "TALK",
 * red "END TALK" (`ui/Theme.kt` `Palette.Talk` / `Palette.TalkOpen`; the notification says
 * "Talk" / "End talk").
 *
 * Colours are ARGB ints and not resources on purpose: the overlay is drawn by hand over another
 * app, and `res/values/colors.xml` carries the same values for everything XML.
 */
enum class OverlayLook(
    val label: String,
    /** Button fill. */
    val fill: Int,
    /** Label colour: dark on the orange (white on it is 2.6:1, UA1), white on the red. */
    val text: Int,
    /** The button breathes while the headset is still switching: pressed, not yet live. */
    val pulsing: Boolean,
) {
    /** No talk: a press opens one. */
    IDLE("TALK", TALK, ON_TALK, pulsing = false),

    /** A talk is open but its microphone is not live yet (the 1–1.5 s route switch). */
    OPENING("END TALK", TALK_OPEN, ON_TALK_OPEN, pulsing = true),

    /** The talk is live: a press ends it. */
    LIVE("END TALK", TALK_OPEN, ON_TALK_OPEN, pulsing = false);

    companion object {
        fun of(talkOpen: Boolean, talkLive: Boolean): OverlayLook = when {
            !talkOpen -> IDLE
            talkLive -> LIVE
            else -> OPENING
        }
    }
}
