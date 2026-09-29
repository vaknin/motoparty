package com.kivan.motoparty.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction

/** What's playing and what comes next. Tap a song to jump to it, ✕ to drop it. */
@Composable
fun QueueTab(s: LinkStatus, cb: Callbacks, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize()) {
        val now = s.nowPlaying
        if (now == null && s.queue.isEmpty()) {
            item {
                EmptyState(Icons.Queue, "The queue is empty", "Songs you add on the Search tab, or on the passenger's phone, show up here.")
            }
            item {
                Button(onClick = onSearch, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(Icons.Search, null)
                    Text("Search", Modifier.padding(start = 8.dp))
                }
            }
            return@LazyColumn
        }
        if (now != null) {
            item { Heading(if (s.playing) "Now playing" else "Paused") }
            item { TrackRow(now.title, byline(now.artist, now.album), now.art, highlighted = true) }
        }
        item {
            Heading("Up next · ${s.queue.size}") {
                if (s.queue.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (s.queue.isEmpty()) {
            item {
                Text(
                    "Nothing after this song.",
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(s.queue, key = { i, t -> "$i/${t.id}" }) { i, t ->
            TrackRow(
                t.title,
                byline(t.artist, t.durationMs.takeIf { it > 0 }?.let(::mmss)),
                t.art,
                onClick = { cb.onAction(UiAction.Jump(i, t.id)) },
                trailing = {
                    IconButton(onClick = { cb.onAction(UiAction.Remove(i, t.id)) }) {
                        Icon(Icons.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
