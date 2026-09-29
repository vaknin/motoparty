package com.kivan.motoparty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.trigger.TriggerKind

/** The screen for the road: who is connected, what is playing, and the one big button. */
@Composable
fun RideTab(
    s: LinkStatus,
    permissions: List<Permission>,
    cb: Callbacks,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusBar(s, cb)
        if (permissions.any { !it.granted }) PermissionsCard(permissions, cb.onGrant)
        NowPlayingCard(s, cb, onOpenQueue)
        BigButton(
            title = if (s.talkOpen) "END TALK" else "TALK",
            icon = Icons.Mic,
            color = if (s.talkOpen) Palette.TalkOpen else Palette.Talk,
            modifier = Modifier.fillMaxWidth(),
        ) { cb.onTrigger(TriggerKind.TALK) }
        Text(
            "In a talk, say “Moto party, play album …”, “… next”, “… pause”, “… over”." +
                if (s.clientName == null) " Alone, the “Moto party” is optional." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun StatusBar(s: LinkStatus, cb: Callbacks) {
    val (dot, title, detail) = when {
        !s.running -> Triple(Palette.Off, "Motoparty is off", "Start it to connect and play")
        s.clientName != null -> Triple(Palette.Good, "Passenger connected", s.clientName)
        else -> Triple(Palette.Waiting, "Waiting for the passenger", s.nsdName?.let { "Visible as $it" } ?: "Starting…")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.running) OutlinedButton(onClick = cb.onStop) { Text("Stop") } else Button(onClick = cb.onStart) { Text("Start") }
    }
}

@Composable
private fun PermissionsCard(permissions: List<Permission>, onGrant: (Permission) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Motoparty needs a few permissions", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            for (p in permissions.filter { !it.granted }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    TextButton(onClick = { onGrant(p) }) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(s: LinkStatus, cb: Callbacks, onOpenQueue: () -> Unit) {
    val t = s.nowPlaying
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Art(t?.art, 104.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        t?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (t == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        t?.artist ?: "Find something on the Search tab, or press TALK and say “Moto party, play …”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (t != null) {
                val fraction = if (t.durationMs > 0) (s.positionMs.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                    Row {
                        Small(mmss(s.positionMs))
                        Spacer(Modifier.weight(1f))
                        Small(mmss(t.durationMs))
                    }
                }
                Transport(s.playing) { cb.onAction(UiAction.Control(it)) }
            }
            s.busy?.let { Text(it, color = Palette.Waiting, style = MaterialTheme.typography.bodyMedium) }
            s.lastAnnounce?.let { Small("“$it”") }
            s.queue.firstOrNull()?.let { next ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenQueue).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Queue, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Up next: ${next.title}" + if (s.queue.size > 1) "  +${s.queue.size - 1}" else "",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Transport(playing: Boolean, control: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { control(ControlAction.PREVIOUS) }, Modifier.size(64.dp)) {
            Icon(Icons.Previous, "Previous", Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = { control(if (playing) ControlAction.PAUSE else ControlAction.RESUME) },
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Icon(
                if (playing) Icons.Pause else Icons.Play,
                if (playing) "Pause" else "Play",
                Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.surface,
            )
        }
        IconButton(onClick = { control(ControlAction.NEXT) }, Modifier.size(64.dp)) {
            Icon(Icons.Next, "Next", Modifier.size(36.dp))
        }
    }
}

@Composable
private fun Small(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Glove-sized: the same one action the overlay and the headset trigger. */
@Composable
private fun BigButton(title: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(128.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(34.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}
