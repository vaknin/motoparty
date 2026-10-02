package com.kivan.motoparty.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kivan.motoparty.PlaybackAnchor
import com.kivan.motoparty.lyrics.LyricLine
import com.kivan.motoparty.lyrics.LyricsPosition
import com.kivan.motoparty.lyrics.LyricsView
import com.kivan.motoparty.ui.UiPrefs.Companion.LYRICS_OFFSET_STEP_MS
import java.util.Locale

/**
 * Synced lyrics in place of the cover (PROTOCOL.md "Tracks", Lyrics): the line before, the current
 * one large with its words lit as they are sung, the next two. [offsetMs] is this track's offset:
 * the lyrics position is the music's minus it. Ticks once a frame, but only while it is on screen
 * and the music plays, and recomposes only when the line or the sung word count changes. [compact]
 * (a short Ride screen): at most one line after, and the offset buttons are left to the caller
 * ([LyricsOffset]), which puts them beside the title.
 */
@Composable
fun LyricsPane(
    view: LyricsView?,
    anchor: PlaybackAnchor?,
    durationMs: Long,
    offsetMs: Int,
    onOffset: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val lines = view?.lines.orEmpty()
            when {
                view == null || view.kind == LyricsView.Kind.LOADING -> Notice("Looking for lyrics…", spinner = true)
                view.kind == LyricsView.Kind.NOT_FOUND || lines.isEmpty() -> Notice("No lyrics found")
                view.kind == LyricsView.Kind.OFFLINE -> Notice("No lyrics yet: no coverage")
                else -> Lines(lines, anchor, durationMs, offsetMs, compact)
            }
        }
        if (!compact && view?.kind == LyricsView.Kind.FOUND) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { LyricsOffset(offsetMs, onOffset) }
        }
    }
}

@Composable
private fun Notice(text: String, spinner: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (spinner) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Lines(lines: List<LyricLine>, anchor: PlaybackAnchor?, durationMs: Long, offsetMs: Int, compact: Boolean) {
    fun position() = LyricsPosition.at(lines, (anchor?.at(SystemClock.elapsedRealtime(), durationMs) ?: 0) - offsetMs)
    var pos by remember(lines) { mutableStateOf(position()) }
    LaunchedEffect(lines, anchor, offsetMs) {
        pos = position()
        // Composed only while the pane shows, and no frames come while the app is in the background.
        if (anchor?.playing == true) {
            while (true) {
                withFrameMillis {
                    val p = position()
                    if (p != pos) pos = p
                }
            }
        }
    }
    AnimatedContent(
        targetState = pos.line,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(tween(260)) { if (up) it / 3 else -it / 3 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(260)) { if (up) -it / 3 else it / 3 } + fadeOut(tween(180)))
        },
        label = "lyrics",
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        // The outgoing line keeps every word lit; the current one follows the music.
        val sung = if (index == pos.line) pos.sung else Int.MAX_VALUE
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // As many lines around the current one as fit (it takes up to three rows of its own):
        // the next one first, then the one before, then a second next.
        val around = ((maxHeight - (if (compact) 56.dp else 96.dp)) / ContextRow).toInt().coerceIn(0, if (compact) 1 else 3)
        val before = if (around >= 2) 1 else 0
        val after = around - before
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (before > 0) {
                Text(
                    lines.getOrNull(index - 1)?.let(::plain) ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = dim.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Current(lines.getOrNull(index), sung, compact)
            for (k in 1..after) {
                Text(
                    lines.getOrNull(index + k)?.let(::plain) ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        }
    }
}

/** A context line's row: titleMedium plus the spacing. */
private val ContextRow = 36.dp

/** The line being sung: [sung] words in full colour, the rest dimmed. Before the first line, a "♪". */
@Composable
private fun Current(line: LyricLine?, sung: Int, compact: Boolean) {
    val on = MaterialTheme.colorScheme.onSurface
    val upcoming = on.copy(alpha = 0.38f)
    val text = if (line == null || line.words.isEmpty()) {
        buildAnnotatedString { withStyle(SpanStyle(color = on)) { append(NOTE) } }
    } else {
        buildAnnotatedString {
            line.words.forEachIndexed { i, w ->
                if (i > 0) append(' ')
                withStyle(SpanStyle(color = if (i < sung) on else upcoming)) { append(w.text) }
            }
        }
    }
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        maxLines = if (compact) 2 else 3,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

private fun plain(line: LyricLine) = if (line.words.isEmpty()) NOTE else line.words.joinToString(" ") { it.text }

private const val NOTE = "♪"

/** This track's offset in ±0.2 s steps: "−0.2 s" shows the lyrics sooner. */
@Composable
fun LyricsOffset(offsetMs: Int, onOffset: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onOffset(offsetMs - LYRICS_OFFSET_STEP_MS) }, Modifier.heightIn(min = 40.dp)) {
            Text("−0.2 s", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "Lyrics ${offsetLabel(offsetMs)}",
            style = MaterialTheme.typography.labelLarge.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onOffset(offsetMs + LYRICS_OFFSET_STEP_MS) }, Modifier.heightIn(min = 40.dp)) {
            Text("+0.2 s", style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal fun offsetLabel(ms: Int): String = when {
    ms == 0 -> "±0.0 s"
    ms > 0 -> String.format(Locale.ROOT, "+%.1f s", ms / 1000.0)
    else -> String.format(Locale.ROOT, "−%.1f s", -ms / 1000.0)
}
