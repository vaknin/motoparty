package com.kivan.motoparty.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kivan.motoparty.PlaybackAnchor
import com.kivan.motoparty.music.Track
import kotlinx.coroutines.delay

fun mmss(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

/**
 * A cover image, square and cropped. The music-note tile is underneath: it is what shows while
 * the image loads and when there is none or it failed, and the image fades in over it.
 */
@Composable
fun Art(url: String?, size: Dp, modifier: Modifier = Modifier, placeholder: ImageVector = Icons.Note, round: Boolean = false) {
    // Round for an artist's picture (2026-10-02), as music apps tell artists from albums.
    val shape = if (round) CircleShape else RoundedCornerShape(if (size >= 160.dp) 20.dp else if (size >= 96.dp) 14.dp else 8.dp)
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, Modifier.size(size * 0.45f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One line of a list, glove-sized (72 dp): art, two lines of text, optional trailing content. */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    art: String?,
    modifier: Modifier = Modifier,
    placeholder: ImageVector = Icons.Note,
    highlighted: Boolean = false,
    /** The track is in the cache: a small mark before [subtitle], so it plays without coverage. */
    downloaded: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** An artist's row: round art. */
    roundArt: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Art(art, 52.dp, placeholder = placeholder, round = roundArt)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(
                        Icons.DownloadDone,
                        "Downloaded",
                        Modifier.padding(end = 4.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** "Artist · 3:33", leaving out whichever part is missing. */
fun byline(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

/** A centred message for an empty list, with room for one button under it. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.padding(bottom = 8.dp).size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action()
    }
}

/** A small section heading inside a tab. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing()
    }
}

/**
 * A coloured notice across the screen: what is wrong in [title], what to do in [detail]. The whole
 * banner is the button when [onClick] is set; [onDismiss] adds a ✕.
 */
@Composable
fun Banner(
    icon: ImageVector,
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 14.dp, end = if (onDismiss != null) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = color)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, Modifier.size(48.dp)) {
                    Icon(Icons.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ---- the playback position, drawn from the anchor ----

/**
 * `SystemClock.elapsedRealtime`, re-read five times a second while [anchor] is playing and left
 * alone otherwise. Whoever reads it inside a draw lambda or a `derivedStateOf` is the only thing
 * that redraws: the position never goes through [com.kivan.motoparty.LinkStatus] (UA4).
 */
@Composable
fun rememberPlaybackClock(anchor: PlaybackAnchor?): LongState {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(anchor) {
        now.longValue = SystemClock.elapsedRealtime()
        if (anchor?.playing == true) {
            while (true) {
                delay(200)
                now.longValue = SystemClock.elapsedRealtime()
            }
        }
    }
    return now
}

/** The thin progress line of a track; only its own draw pass runs as the music moves. */
@Composable
fun TrackProgress(anchor: PlaybackAnchor?, now: LongState, durationMs: Long, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { anchor?.fraction(now.longValue, durationMs) ?: 0f },
        modifier = modifier,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/** "1:37", recomposed once a second at most. */
@Composable
fun PositionText(anchor: PlaybackAnchor?, now: LongState, durationMs: Long, modifier: Modifier = Modifier) {
    val text by remember(anchor, durationMs) { derivedStateOf { mmss(anchor?.at(now.longValue, durationMs) ?: 0) } }
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodyMedium.tabular,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What is playing, on every tab but Ride: 64 dp above the navigation bar. A tap goes to Ride;
 * the one button is play/pause.
 */
@Composable
fun MiniPlayer(
    track: Track,
    playing: Boolean,
    anchor: PlaybackAnchor?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberPlaybackClock(anchor)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open the Ride tab", onClick = onOpen)) {
            TrackProgress(anchor, now, track.durationMs, Modifier.fillMaxWidth().height(2.dp))
            Row(
                Modifier.height(64.dp).padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Art(track.art, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        // Scrolls when it does not fit, like the title on the Ride tab.
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(onClick = onPlayPause, Modifier.size(52.dp)) {
                    Icon(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", Modifier.size(30.dp))
                }
            }
        }
    }
}
