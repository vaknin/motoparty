package com.kivan.motoparty.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.ControlAction

/** What's playing and what comes next. Tap a song to jump to it, ✕ to drop it (with Undo). */
@Composable
fun QueueTab(s: LinkStatus, cb: Callbacks, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
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
    val keys = remember(s.queue) { queueKeys(s.queue) }
    LazyColumn(modifier.fillMaxSize()) {
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
        itemsIndexed(s.queue, key = { i, _ -> keys[i] }) { i, t ->
            TrackRow(
                t.title,
                byline(t.artist, t.durationMs.takeIf { it > 0 }?.let(::mmss)),
                t.art,
                Modifier.animateItem(),
                onClick = { cb.onAction(UiAction.Jump(i, t.id)) },
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
