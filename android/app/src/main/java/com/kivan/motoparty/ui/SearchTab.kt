package com.kivan.motoparty.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.motoparty.BrowseState
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.EnqueueMode
import com.kivan.motoparty.core.SearchKind
import com.kivan.motoparty.music.CollectionItem
import com.kivan.motoparty.music.History
import com.kivan.motoparty.music.RecentSearch
import com.kivan.motoparty.music.Track

/**
 * Search YouTube Music; tap a song to play it, open an album or playlist to see its songs first.
 * With the box empty: recent searches and recently played ([History]).
 */
@Composable
fun SearchTab(s: LinkStatus, cb: Callbacks, modifier: Modifier = Modifier, history: History = History()) {
    val browse = s.browse
    if (browse != null) {
        BackHandler { cb.onAction(UiAction.CloseBrowse) }
        CollectionScreen(browse, s, cb, modifier)
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
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    placeholder = { Text("Songs, albums, artists") },
                    leadingIcon = { Icon(Icons.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }, Modifier.size(56.dp)) { Icon(Icons.Close, "Clear") }
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
                            label = { Text(label, style = MaterialTheme.typography.titleSmall) },
                            modifier = Modifier.height(48.dp),
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
        // The text in the box was edited since: say whose results these are (UA10).
        val stale = shown && !r.loading && r.error == null && r.query != query.trim()
        if (stale && query.isNotBlank()) item(key = "stale") { Heading("Results for “${r.query}”") }
        when {
            !s.running -> item { EmptyState(Icons.Search, "Motoparty is off", "Start it on the Ride tab to search.") }
            query.isBlank() && (history.searches.isNotEmpty() || history.played.isNotEmpty()) -> {
                if (history.searches.isNotEmpty()) {
                    item {
                        Heading("Recent searches") {
                            TextButton(onClick = { cb.onHistory { it.withoutSearches() } }) { Text("Clear") }
                        }
                    }
                    items(history.searches, key = { "q/${it.query}" }) { past ->
                        RecentSearchRow(past) {
                            query = past.query
                            kind = past.kind
                            run(past.kind)
                        }
                    }
                }
                if (history.played.isNotEmpty()) {
                    item { Heading("Recently played") }
                    items(history.played, key = { "p/${it.id}" }) { t ->
                        SongRow(t, highlighted = t.id == s.nowPlaying?.id, downloaded = t.id in s.cached, cb)
                    }
                }
            }
            shown && r.loading -> item { Loading() }
            shown && r.error != null -> item { EmptyState(Icons.Search, r.error, "Try again when there is signal.") }
            shown && kind == SearchKind.SONGS && r.songs.isNotEmpty() -> itemsIndexed(r.songs, key = { i, t -> "s/$i/${t.id}" }) { _, t ->
                SongRow(t, highlighted = t.id == s.nowPlaying?.id, downloaded = t.id in s.cached, cb)
            }
            shown && kind != SearchKind.SONGS && r.collections.isNotEmpty() -> itemsIndexed(r.collections, key = { i, c -> "c/$i/${c.id}" }) { _, c ->
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
                    "Tap a song to play it now. The button beside it plays it next or adds it to the queue. " +
                        "Albums and playlists open first, so you can see what's in them.",
                )
            }
        }
    }
}

private val KINDS = listOf(SearchKind.SONGS to "Songs", SearchKind.ALBUMS to "Albums", SearchKind.PLAYLISTS to "Playlists")

/** A past search: tap runs it again, with the chip it had. */
@Composable
private fun RecentSearchRow(r: RecentSearch, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.History, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            r.query,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            KINDS.firstOrNull { it.first == r.kind }?.second ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SongRow(t: Track, highlighted: Boolean, downloaded: Boolean, cb: Callbacks, onPlay: (() -> Unit)? = null) {
    TrackRow(
        t.title,
        byline(t.artist, t.durationMs.takeIf { it > 0 }?.let(::mmss)),
        t.art,
        highlighted = highlighted,
        downloaded = downloaded,
        onClick = onPlay ?: { cb.onAction(UiAction.Enqueue(EnqueueMode.NOW, listOf(t))) },
        trailing = { QueueMenu(t.title) { mode -> cb.onAction(UiAction.Enqueue(mode, listOf(t))) } },
    )
}

/** ⋮ with "Play next" and "Add to queue": a real button, not a gesture, so it works with gloves. */
@Composable
private fun QueueMenu(title: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(60.dp)) { Icon(Icons.More, "More options for $title") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.Play, null) },
                onClick = { open = false; onPick(EnqueueMode.NEXT) },
                modifier = Modifier.heightIn(min = 60.dp),
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.QueueAdd, null) },
                onClick = { open = false; onPick(EnqueueMode.END) },
                modifier = Modifier.heightIn(min = 60.dp),
            )
        }
    }
}

@Composable
private fun CollectionScreen(b: BrowseState, s: LinkStatus, cb: Callbacks, modifier: Modifier) {
    val c: CollectionItem = b.collection
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { cb.onAction(UiAction.CloseBrowse) }, Modifier.size(56.dp)) { Icon(Icons.Back, "Back") }
                Text("Search", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    textAlign = TextAlign.Center,
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
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.Play, null)
                        Text("Play", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { cb.onAction(UiAction.Enqueue(EnqueueMode.END, b.tracks)) },
                        enabled = ready,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.QueueAdd, null)
                        Text("Add to queue", Modifier.padding(start = 8.dp))
                    }
                }
                if (b.tracks.isNotEmpty()) DownloadButton(b, s, cb)
            }
        }
        when {
            b.loading -> item { Loading() }
            b.error != null -> item { EmptyState(Icons.Album, b.error, "Try again when there is signal.") }
            b.tracks.isEmpty() -> item { EmptyState(Icons.Album, "No songs in this one") }
            // Tapping a song plays the collection from there, so the rest of it follows.
            else -> itemsIndexed(b.tracks, key = { i, t -> "b/$i/${t.id}" }) { i, t ->
                SongRow(t, highlighted = t.id == s.nowPlaying?.id, downloaded = t.id in s.cached, cb) {
                    cb.onAction(UiAction.Enqueue(EnqueueMode.NOW, b.tracks.drop(i)))
                }
            }
        }
    }
}

/**
 * Every song of this album or playlist into the cache, for patchy coverage. Shows the progress
 * ("Downloading 5/14") and cancels on a second tap; "Downloaded" once every song is cached.
 */
@Composable
private fun DownloadButton(b: BrowseState, s: LinkStatus, cb: Callbacks) {
    val p = s.downloads[b.collection.id]
    val allCached = b.tracks.all { it.id in s.cached }
    val running = p?.running == true
    val done = !running && (allCached || (p != null && p.failed == 0))
    OutlinedButton(
        onClick = {
            if (running) cb.onAction(UiAction.CancelDownload(b.collection.id))
            else cb.onAction(UiAction.Download(b.collection, b.tracks))
        },
        enabled = !done,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (running) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(if (done) Icons.DownloadDone else Icons.Download, null)
        }
        Text(
            when {
                done -> "Downloaded"
                p != null -> p.label + if (running) "  ·  Stop" else ""
                else -> "Download"
            },
            Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(36.dp))
    }
}
