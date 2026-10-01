package com.kivan.motoparty.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.music.Track
import kotlinx.coroutines.delay

/**
 * What's playing and what comes next. Tap a song to jump to it, ✕ to drop it (with Undo), press
 * and hold to drag it somewhere else in the queue; held at the top or bottom edge, the list scrolls
 * under it.
 */
@Composable
fun QueueTab(s: LinkStatus, cb: Callbacks, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val drag = remember { QueueDrag() }
    val haptics = LocalHapticFeedback.current
    // The host's answer to a drop is a new queue: the rows the drag left behind give way to it.
    LaunchedEffect(s.queue) { if (drag.key == null) drag.order = null }
    // A drop the host ignored (the queue changed meanwhile) changes nothing: show the queue again.
    LaunchedEffect(drag.droppedAt) {
        if (drag.droppedAt == 0L) return@LaunchedEffect
        delay(DROP_WAIT_MS)
        if (drag.key == null) drag.order = null
    }
    val now = s.nowPlaying
    if (now == null && s.queue.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Queue,
                "The queue is empty",
                "Songs you add on the Search tab, or on the passenger's phone, show up here.",
            ) {
                Button(onClick = onSearch, Modifier.padding(top = 16.dp).height(56.dp)) {
                    Icon(Icons.Search, null)
                    Text("Search for music", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }
    val queue = drag.order ?: s.queue
    val keys = remember(queue) { queueKeys(queue) }
    val latest by rememberUpdatedState(s.queue)
    // The lazy index of the first upcoming row: the headings and the "nothing next" line come first.
    val firstRow = (if (now != null) 2 else 0) + 1 + (if (s.queue.isEmpty()) 1 else 0)
    SideEffect { drag.firstRow = firstRow }
    val density = LocalDensity.current
    // A held row near an edge scrolls the list, faster the deeper the finger is into the edge zone,
    // and the row stays under the finger while the others pass beneath it (QueueDrag.scrolled).
    LaunchedEffect(drag.key != null) {
        if (drag.key == null) return@LaunchedEffect
        val zone = with(density) { EDGE_ZONE.toPx() }
        val max = with(density) { EDGE_SPEED_PER_S.toPx() }
        var last = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val seconds = (frame - last) / 1e9f
            last = frame
            val y = drag.fingerY(listState) ?: continue
            val info = listState.layoutInfo
            val speed = edgeScrollSpeed(y, info.viewportStartOffset.toFloat(), info.viewportEndOffset.toFloat(), zone, max)
            if (speed == 0f) continue
            // 0 at either end of the list: the scroll just stops there.
            val moved = listState.dispatchRawDelta(speed * seconds)
            if (moved != 0f) drag.scrolled(moved, listState)
        }
    }
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        if (now != null) {
            item(key = "now-heading") { Heading(if (s.playing) "Now playing" else "Paused", Modifier.animateItem()) }
            item(key = "now") {
                TrackRow(
                    now.title,
                    byline(now.artist, now.album),
                    now.art,
                    Modifier.animateItem(),
                    highlighted = true,
                    trailing = {
                        FilledTonalIconButton(
                            onClick = { cb.onAction(UiAction.Control(if (s.playing) ControlAction.PAUSE else ControlAction.RESUME)) },
                            modifier = Modifier.padding(end = 4.dp).size(56.dp),
                        ) {
                            Icon(if (s.playing) Icons.Pause else Icons.Play, if (s.playing) "Pause" else "Play", Modifier.size(30.dp))
                        }
                    },
                )
            }
        }
        item(key = "next-heading") {
            Heading("Up next · ${s.queue.size}", Modifier.animateItem()) {
                if (s.queue.isNotEmpty()) TextButton(onClick = { confirmClear = true }, Modifier.height(48.dp)) { Text("Clear") }
            }
        }
        if (s.queue.isEmpty()) {
            item(key = "nothing-next") {
                Text(
                    "Nothing after this song.",
                    Modifier.animateItem().padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Keys that stay put when another row goes, so the rest slides up instead of redrawing.
        itemsIndexed(queue, key = { i, _ -> keys[i] }) { i, t ->
            val key = keys[i]
            val dragged = drag.key == key
            TrackRow(
                t.title,
                byline(t.artist, t.durationMs.takeIf { it > 0 }?.let(::mmss)),
                t.art,
                Modifier.pointerInput(key) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            if (drag.start(key, latest, at.y)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.by(amount.y, listState)
                        },
                        onDragEnd = { drag.drop()?.let(cb.onAction) },
                        onDragCancel = { drag.drop()?.let(cb.onAction) },
                    )
                }.then(
                    // The dragged row follows the finger above the others; the others slide out of its way.
                    if (dragged) {
                        Modifier.zIndex(1f)
                            .graphicsLayer { translationY = drag.offset; shadowElevation = 8.dp.toPx() }
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        Modifier.animateItem()
                    },
                ),
                // A press that became a drag is not also a tap.
                onClick = { if (!drag.justDropped()) cb.onAction(UiAction.Jump(i, t.id)) },
                trailing = {
                    IconButton(onClick = { cb.onAction(UiAction.Remove(i, t.id)) }, Modifier.size(60.dp)) {
                        Icon(Icons.Close, "Remove ${t.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the queue?") },
            text = { Text("The ${s.queue.size} upcoming songs are removed. The current song keeps playing.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; cb.onAction(UiAction.ClearQueue) }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** How long the rows a drop left behind wait for the host's new queue before they give up. */
private const val DROP_WAIT_MS = 1_500L

/** How deep the edge zones are that scroll the list while a row is held ([edgeScrollSpeed]). */
private val EDGE_ZONE: Dp = 56.dp

/** The scroll speed with the finger at the very edge, per second: about a Pixel 8 screen of queue. */
private val EDGE_SPEED_PER_S: Dp = 900.dp

/**
 * Drag to reorder the upcoming rows (PROTOCOL.md "Browsing", `music.edit move`): while a row is
 * held, [order] is the queue as the screen shows it, with the row moved wherever it was dragged;
 * the drop sends one [UiAction.Move] for the whole way. The host answers with a new queue.
 */
@Stable
private class QueueDrag {
    /** The rows while a drag runs and until the host answers; null = the queue as it is. */
    var order by mutableStateOf<List<Track>?>(null)
    /** The held row's key ([queueKeys]); null = no drag. */
    var key by mutableStateOf<String?>(null)
    /** How far the held row is from its place in [order], in px. */
    var offset by mutableFloatStateOf(0f)
    /** When the last drop happened ([SystemClock.uptimeMillis]); 0 = none yet. */
    var droppedAt by mutableLongStateOf(0L)
    /** The lazy index of [order]'s first row (set on every composition). */
    var firstRow = 0
    private var from = -1
    private var id = ""
    /** Where the finger is on the held row, px from its top. */
    private var grabY = 0f

    /** [key]'s row of [queue] is held, by a finger [grabY] px below its top. False when it is not there any more. */
    fun start(key: String, queue: List<Track>, grabY: Float): Boolean {
        val i = queueKeys(queue).indexOf(key)
        if (i < 0) return false
        order = queue
        this.key = key
        from = i
        id = queue[i].id
        offset = 0f
        this.grabY = grabY
        return true
    }

    /** Where the finger is, px from the top of [list]'s viewport; null = no drag, or the row is not laid out. */
    fun fingerY(list: LazyListState): Float? {
        val k = key ?: return null
        val me = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } ?: return null
        return me.offset + offset + grabY
    }

    /**
     * The list scrolled [px] under the held row (positive = toward the end): every row moved up by
     * [px], so the held row's offset grows by as much to stay under the finger, and the rows it
     * now covers swap with it as if the finger had moved.
     */
    fun scrolled(px: Float, list: LazyListState) = by(px, list)

    /**
     * The finger moved [dy] px. Once the held row's middle passes a neighbour's middle, the two
     * swap in [order], and the offset takes the neighbour's height off so the row stays under the finger.
     */
    fun by(dy: Float, list: LazyListState) {
        val k = key ?: return
        val rows = order ?: return
        offset += dy
        val visible = list.layoutInfo.visibleItemsInfo
        val me = visible.firstOrNull { it.key == k } ?: return
        val keys = queueKeys(rows)
        val i = keys.indexOf(k)
        // The last swap is not laid out yet: the rows on screen are still in the old order.
        if (me.index != firstRow + i) return
        val j = if (offset > 0) i + 1 else i - 1
        val other = keys.getOrNull(j)?.let { nk -> visible.firstOrNull { it.key == nk } } ?: return
        val middle = me.offset + me.size / 2 + offset
        val passed = if (offset > 0) middle > other.offset + other.size / 2 else middle < other.offset + other.size / 2
        // The same song twice: swapping the two would only change which one is held.
        if (!passed || rows[j].id == rows[i].id) return
        // A LazyColumn keeps its first visible row in place by key; when that row is one of the two
        // swapping, it would scroll after it. Pin the scroll position by index instead.
        if (list.firstVisibleItemIndex == me.index || list.firstVisibleItemIndex == other.index) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        order = rows.toMutableList().apply { add(j, removeAt(i)) }
        key = queueKeys(order!!)[j]
        offset += if (offset > 0) -other.size.toFloat() else other.size.toFloat()
    }

    /** The finger let go: the move to send, or null when the row is back where it was. */
    fun drop(): UiAction? {
        val k = key ?: return null
        val to = order?.let { queueKeys(it).indexOf(k) } ?: -1
        key = null
        offset = 0f
        droppedAt = SystemClock.uptimeMillis()
        if (to < 0 || to == from) {
            order = null
            return null
        }
        return UiAction.Move(from, id, to)
    }

    /** A drop just ended: the tap that may follow the same press is not one. */
    fun justDropped(): Boolean = SystemClock.uptimeMillis() - droppedAt < TAP_AFTER_DROP_MS

    private companion object {
        const val TAP_AFTER_DROP_MS = 400L
    }
}
