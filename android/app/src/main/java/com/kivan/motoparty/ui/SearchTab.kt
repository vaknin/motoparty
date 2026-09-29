package com.kivan.motoparty.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.motoparty.BrowseState
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.core.SearchKind
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.Track

/** Search YouTube Music; tap a song to play it, open an album or playlist to see its songs first. */
@Composable
fun SearchTab(s: LinkStatus, cb: Callbacks, modifier: Modifier = Modifier) {
    val browse = s.browse
    if (browse != null) {
        BackHandler { cb.onAction(UiAction.CloseBrowse) }
        CollectionScreen(browse, cb, modifier)
        return
    }
    var query by rememberSaveable { mutableStateOf(s.search.query) }
    var kind by rememberSaveable { mutableStateOf(s.search.kind) }
    val focus = LocalFocusManager.current
    fun run(k: String = kind) {
        if (query.isBlank() || !s.running) return
        focus.clearFocus()
        cb.onAction(UiAction.Search(k, query.trim()))
    }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Songs, albums, artists") },
                    leadingIcon = { Icon(Icons.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Close, "Clear") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, label) in KINDS) {
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k; run(k) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
        }
        val r = s.search
        val shown = r.kind == kind && r.query.isNotEmpty()
        when {
            !s.running -> item { EmptyState(Icons.Search, "Motoparty is off", "Start it on the Ride tab to search.") }
            shown && r.loading -> item { Loading() }
            shown && r.error != null -> item { EmptyState(Icons.Search, r.error, "Try again when there is signal.") }
            shown && kind == SearchKind.SONGS && r.songs.isNotEmpty() -> itemsIndexed(r.songs) { _, t ->
                SongRow(t, highlighted = t.id == s.nowPlaying?.id, cb)
            }
            shown && kind != SearchKind.SONGS && r.collections.isNotEmpty() -> itemsIndexed(r.collections) { _, c ->
                TrackRow(
                    c.title,
                    byline(c.artist, c.count?.let { "$it songs" }),
                    c.art,
                    placeholder = Icons.Album,
                    onClick = { cb.onAction(UiAction.Browse(c)) },
                )
            }
            shown -> item { EmptyState(Icons.Search, "Nothing found for “${r.query}”") }
            else -> item {
                EmptyState(
                    Icons.Note,
                    "Search YouTube Music",
                    "Tap a song to play it now, or ⋮ to play it next or add it to the queue. " +
                        "Albums and playlists open first, so you can see what's in them.",
                )
            }
        }
    }
}

private val KINDS = listOf(SearchKind.SONGS to "Songs", SearchKind.ALBUMS to "Albums", SearchKind.PLAYLISTS to "Playlists")

@Composable
private fun SongRow(t: Track, highlighted: Boolean, cb: Callbacks, onPlay: (() -> Unit)? = null) {
    TrackRow(
        t.title,
        byline(t.artist, t.durationMs.takeIf { it > 0 }?.let(::mmss)),
        t.art,
        highlighted = highlighted,
        onClick = onPlay ?: { cb.onAction(UiAction.Enqueue(EnqueueMode.NOW, listOf(t))) },
        trailing = { QueueMenu { mode -> cb.onAction(UiAction.Enqueue(mode, listOf(t))) } },
    )
}

/** ⋮ with "Play next" and "Add to queue": a real button, not a gesture, so it works with gloves. */
@Composable
private fun QueueMenu(onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.More, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.Play, null) },
                onClick = { open = false; onPick(EnqueueMode.NEXT) },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.QueueAdd, null) },
                onClick = { open = false; onPick(EnqueueMode.END) },
            )
        }
    }
}

@Composable
private fun CollectionScreen(b: BrowseState, cb: Callbacks, modifier: Modifier) {
    val c: CollectionItem = b.collection
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { cb.onAction(UiAction.CloseBrowse) }) { Icon(Icons.Back, "Back") }
                Text("Search", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Art(c.art, 180.dp, placeholder = Icons.Album)
                Text(
                    c.title,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val count = if (b.tracks.isNotEmpty()) b.tracks.size else c.count
                Text(
                    byline(c.artist, count?.let { "$it songs" }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val ready = b.tracks.isNotEmpty()
                    Button(
                        onClick = { cb.onAction(UiAction.Enqueue(EnqueueMode.NOW, b.tracks)) },
                        enabled = ready,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Play, null)
                        Text("Play", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { cb.onAction(UiAction.Enqueue(EnqueueMode.END, b.tracks)) },
                        enabled = ready,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.QueueAdd, null)
                        Text("Add to queue", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        when {
            b.loading -> item { Loading() }
            b.error != null -> item { EmptyState(Icons.Album, b.error, "Try again when there is signal.") }
            b.tracks.isEmpty() -> item { EmptyState(Icons.Album, "No songs in this one") }
            // Tapping a song plays the collection from there, so the rest of it follows.
            else -> itemsIndexed(b.tracks) { i, t ->
                SongRow(t, highlighted = false, cb) { cb.onAction(UiAction.Enqueue(EnqueueMode.NOW, b.tracks.drop(i))) }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(36.dp))
    }
}
