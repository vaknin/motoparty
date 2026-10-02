package com.kivan.motoparty.ui

import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.music.MusicPhase
import com.kivan.motoparty.music.Track
import java.util.Locale

/*
 * What the screens decide, kept out of the composables so it is tested on the JVM
 * (LogicTest): the talk button's state, the wording, the snackbars, the queue's keys.
 */

/** The talk button's four states. The 1–1.5 s headset switch is [OPENING], not a dead button. */
enum class TalkPhase(val label: String) {
    IDLE("TALK"),

    /** Opened, the microphone is not live yet. */
    OPENING("Connecting…"),

    /** The live earcon has fired: speak. */
    LIVE("END TALK"),

    /** Closed, the headset is switching back to music. */
    CLOSING("Ending…"),
    ;

    companion object {
        fun of(s: LinkStatus): TalkPhase = when {
            s.talkOpen && s.talkLive -> LIVE
            s.talkOpen -> OPENING
            s.talkClosing -> CLOSING
            else -> IDLE
        }
    }
}

/** The link in words: one name per state, the same on every screen. */
data class LinkLine(val kind: Kind, val title: String, val detail: String) {
    enum class Kind { OFF, WAITING, CONNECTED }

    companion object {
        fun of(s: LinkStatus): LinkLine = when {
            !s.running -> LinkLine(Kind.OFF, "Motoparty is off", "Start it to connect and play")
            s.clientName != null -> LinkLine(Kind.CONNECTED, "Passenger connected", s.clientName)
            s.nsdName == null -> LinkLine(Kind.WAITING, "Starting…", "Getting ready for the passenger")
            else -> LinkLine(Kind.WAITING, "Waiting for the passenger", "Ready — open Motoparty on the iPhone")
        }
    }
}

/** Why the track sits at its position: loading, waiting for the passenger, or held by a talk. */
fun phaseText(phase: MusicPhase, clientName: String?): String = when (phase) {
    MusicPhase.LOADING -> "Downloading song…"
    MusicPhase.WAITING_CLIENT -> "Waiting for ${clientName ?: "the passenger"}…"
    MusicPhase.PAUSED_FOR_TALK -> "Paused for talk — plays when the talk ends"
    MusicPhase.ON_CALL -> "Muted for your call — ${clientName ?: "the passenger"} keeps listening"
}

// ---- the microphone meter of a live talk ----

/** Below this the bar is empty: about the hiss of an open microphone. */
private const val METER_FLOOR_DB = -48f

/** At this the bar is full; speech through the headset peaks a little under it. */
private const val METER_FULL_DB = -6f

/** How long a full bar takes to fall to nothing once the rider stops speaking. */
private const val METER_FALL_MS = 450f

/** How often the Ride tab reads [com.kivan.motoparty.audio.MicLevel]: 16 times a second. */
const val METER_POLL_MS = 60L

/**
 * A frame's loudest sample (0…32768) as the fill of the meter, 0…1, on a decibel scale: every
 * 6 dB (a doubling of the sample) is the same step of the bar, so quiet speech already shows.
 */
fun micLevel(peak: Int): Float {
    if (peak <= 0) return 0f
    val db = 20f * kotlin.math.log10(peak / 32768f)
    return ((db - METER_FLOOR_DB) / (METER_FULL_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)
}

/**
 * What the meter shows [elapsedMs] after it showed [shown], given the microphone is at [level]
 * now: it jumps up at once and falls back at a steady rate, so a syllable is seen and the bar
 * does not flicker between frames.
 */
fun meterStep(shown: Float, level: Float, elapsedMs: Long): Float =
    maxOf(level, shown - elapsedMs.coerceAtLeast(0) / METER_FALL_MS).coerceIn(0f, 1f)

/** A snackbar after an action: what happened, and the action that takes it back, if one does. */
data class Confirmation(val text: String, val undo: UiAction? = null)

/**
 * The confirmation for [action] given the state [s] it was pressed in, or null when the screen
 * shows the result by itself (a play, a jump) or the host is off and nothing happens.
 */
fun confirmation(action: UiAction, s: LinkStatus): Confirmation? {
    if (!s.running) return null
    return when (action) {
        is UiAction.Enqueue -> {
            val n = action.tracks.size
            val what = if (n == 1) action.tracks[0].title else "$n songs"
            when {
                n == 0 || action.mode == EnqueueMode.NOW -> null
                // With nothing loaded the host plays it at once; the Ride tab shows that.
                s.nowPlaying == null -> null
                action.mode == EnqueueMode.NEXT -> Confirmation("Playing next: $what")
                n == 1 -> Confirmation("Added to queue: $what")
                else -> Confirmation("Added $what")
            }
        }
        is UiAction.Remove -> s.queue.getOrNull(action.index)?.takeIf { it.id == action.id }?.let {
            Confirmation("Removed: ${it.title}", UiAction.Restore(action.index, it))
        }
        is UiAction.ClearQueue ->
            if (s.queue.isEmpty()) null else Confirmation("Queue cleared", UiAction.Enqueue(EnqueueMode.END, s.queue))
        is UiAction.Download -> Confirmation("Downloading ${action.tracks.size} songs")
        else -> null
    }
}

/**
 * One key per queue row that survives edits elsewhere in the list: the track id plus which
 * occurrence of it this is (a song can be queued twice). Removing row 0 leaves every other key
 * as it was, so the list animates instead of redrawing (UA5).
 */
fun queueKeys(queue: List<Track>): List<String> {
    val seen = HashMap<String, Int>()
    return queue.map { t ->
        val n = (seen[t.id] ?: 0) + 1
        seen[t.id] = n
        "${t.id}#$n"
    }
}

/** "99+" above two digits: the badge stays a badge. */
fun badgeText(count: Int): String = if (count > 99) "99+" else "$count"

/**
 * "Allow" was pressed and the system answered without asking (a permanent denial, UA14): the only
 * way left is the app's page in Settings. A dialog the rider answered takes longer than
 * [INSTANT_MS]; one that was never shown comes back at once, still denied, with no rationale.
 */
fun deniedForGood(granted: Boolean, showRationale: Boolean, answeredAfterMs: Long): Boolean =
    !granted && !showRationale && answeredAfterMs < INSTANT_MS

private const val INSTANT_MS = 400L

/** The speech languages offered in Settings, as BCP-47 tags; [current] is added when it is not one of them. */
fun speechLanguages(current: String): List<String> = (SPEECH_LANGUAGES + current).distinct()

private val SPEECH_LANGUAGES = listOf(
    "en-US", "en-GB", "en-AU", "en-IN", "de-DE", "es-ES", "es-US", "fr-FR", "it-IT", "nl-NL", "pt-BR", "pl-PL", "ru-RU", "he-IL",
)

/** "English (United States)" for `en-US`; the tag itself when there is no name for it. */
fun languageName(tag: String): String {
    val l = Locale.forLanguageTag(tag)
    if (l.language.isEmpty()) return tag
    return l.getDisplayName(Locale.ENGLISH).ifBlank { tag }
}

/** The spoken commands (PROTOCOL.md "Commands"), in rows: long ones alone, the rest in pairs. */
val COMMANDS: List<List<String>> = listOf(
    listOf("play <song>"),
    listOf("play album / artist / playlist <name>"),
    listOf("pause", "resume"),
    listOf("next", "previous"),
    listOf("louder", "quieter"),
    listOf("what's playing", "shuffle"),
    listOf("queue [next] <song>  ·  adds it"),
    listOf("over  ·  only ends the talk"),
)

/** The one-line reminder on the Ride tab; the sheet behind it has [COMMANDS]. */
const val COMMANDS_LINE = "play · pause · next · louder · over …"
