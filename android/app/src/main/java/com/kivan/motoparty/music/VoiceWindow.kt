package com.kivan.motoparty.music

import com.kivan.motoparty.core.Interpretation
import com.kivan.motoparty.core.RepeatMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What the host remembers of a context window it sent (PROTOCOL.md "Commands", *Voice actions*):
 * which track each number stood for. [upNext]`[n-1]` is the id at window position n, which was
 * `upcoming[n-1]` then; [played]`[n-1]` is the track at −n.
 */
data class VoiceSnapshot(val upNext: List<String> = emptyList(), val played: List<Track> = emptyList()) {
    companion object {
        /** For a list that names no positions (the grammar's). */
        val EMPTY = VoiceSnapshot()
    }
}

/** The interpreter's input ([input], one JSON object) and its [snapshot]. */
class VoiceWindow(val input: JsonObject, val snapshot: VoiceSnapshot) {
    /** The first request and the question it got, for the reply to our question. */
    data class Asked(val phrase: String, val question: String)

    companion object {
        /**
         * The window for [phrase]: [current] at [positionMs], the [repeat] mode, the [upcoming]
         * tracks, [history] (the History store's played list, newest first, which starts with the
         * current track once it has played), [lastVoice] (`null` = none) and, for a reply, [asked].
         * The phrase travels in it as data, never as loose prompt text.
         */
        fun build(
            phrase: String,
            lang: String,
            current: Track?,
            positionMs: Long,
            repeat: RepeatMode,
            upcoming: List<Track>,
            history: List<Track>,
            lastVoice: String?,
            asked: Asked? = null,
        ): VoiceWindow {
            val next = upcoming.take(Interpretation.INTERPRET_UP_NEXT)
            val played = history.dropWhile { it.id == current?.id }.take(Interpretation.INTERPRET_PLAYED)
            val input = buildJsonObject {
                put("phrase", phrase)
                put("lang", lang)
                if (current == null) put("playing", JsonNull) else putJsonObject("playing") {
                    put("title", current.title)
                    put("artist", current.artist)
                    current.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                    put("atS", positionMs.coerceAtLeast(0) / 1000)
                    if (current.durationMs > 0) put("lengthS", current.durationMs / 1000)
                }
                put("repeat", repeat.word)
                put("upNext", JsonArray(next.mapIndexed { i, t -> JsonPrimitive("${i + 1}. ${line(t)}") }))
                put("queueLength", upcoming.size)
                put("played", JsonArray(played.mapIndexed { i, t -> JsonPrimitive("-${i + 1}. ${line(t)}") }))
                put("lastVoice", lastVoice)
                // Only on the second turn: the phrase is then the reply to this question.
                if (asked != null) putJsonObject("asked") {
                    put("phrase", asked.phrase)
                    put("question", asked.question)
                }
            }
            return VoiceWindow(input, VoiceSnapshot(next.map { it.id }, played))
        }

        /** `<title> – <artist>`, just the title when the artist is empty. */
        fun line(t: Track): String = if (t.artist.isBlank()) t.title else "${t.title} – ${t.artist}"
    }
}
