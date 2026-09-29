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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.music.MusicPhase
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
        s.larkMissing?.let { LarkWarning(it, s.talkOnEarbudsFallback) }
        if (permissions.any { !it.granted }) PermissionsCard(permissions, cb.onGrant)
        NowPlayingCard(s, cb, onOpenQueue)
        BigButton(
            title = if (s.talkOpen) "END TALK" else "TALK",
            icon = Icons.Mic,
            color = if (s.talkOpen) Palette.TalkOpen else Palette.Talk,
            modifier = Modifier.fillMaxWidth(),
        ) { cb.onTrigger(TriggerKind.TALK) }
        VoiceCommands(solo = s.clientName == null)
    }
}

/**
 * Every spoken command (PROTOCOL.md "Commands"), readable at a glance: a command is the first
 * phrase of a talk, and in a solo talk every phrase is one. Full-width rows for the long ones,
 * pairs for the rest. `<…>` is what the rider fills in, drawn lighter.
 */
@Composable
private fun VoiceCommands(solo: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Voice commands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (solo) "Press TALK and say one. Alone, every phrase is a command."
                else "Press TALK and say one of these first. After that it's just talk.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (row in COMMANDS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in row) CommandChip(c, Modifier.weight(1f))
                }
            }
        }
    }
}

private val COMMANDS = listOf(
    listOf("play <song>"),
    listOf("play album / artist / playlist <name>"),
    listOf("pause", "resume"),
    listOf("next", "previous"),
    listOf("louder", "quieter"),
    listOf("what's playing", "shuffle"),
    listOf("over  ·  ends the talk"),
)

@Composable
private fun CommandChip(text: String, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val styled = buildAnnotatedString {
        // "<name>" and the "· ends the talk" note are not words to say: lighter.
        var rest = text
        while (rest.isNotEmpty()) {
            val at = listOf(rest.indexOf('<'), rest.indexOf('·')).filter { it >= 0 }.minOrNull()
            if (at == null) { append(rest); break }
            append(rest.substring(0, at))
            val end = if (rest[at] == '<') rest.indexOf('>', at).let { if (it < 0) rest.length else it + 1 } else rest.length
            withStyle(SpanStyle(color = dim, fontWeight = FontWeight.Normal)) { append(rest.substring(at, end)) }
            rest = rest.substring(end)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Text(
            styled,
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
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

/**
 * The "Use USB stereo mic (Lark) for talk" setting is on and no receiver is there ([reason]): talk
 * silently falls back to the earbud mics otherwise. Red while a talk is actually running on them.
 */
@Composable
private fun LarkWarning(reason: String, inTalk: Boolean) {
    val color = if (inTalk) Palette.TalkOpen else Palette.Waiting
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Warning, null, Modifier.size(24.dp), tint = color)
            Column(Modifier.weight(1f)) {
                Text(
                    if (inTalk) "This talk is on the earbud mics" else "Lark receiver not detected",
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                Text(
                    if (inTalk) "Lark receiver not detected ($reason). Replug it; the next talk will use it."
                    else "Talk will use the earbud mics ($reason). Replug the receiver.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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
                        t?.artist ?: "Find something on the Search tab, or press TALK and say “play …”",
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
                    s.musicPhase?.let { PhaseLine(it, s.clientName) }
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

/** Why the track sits at its position: loading, waiting for the passenger, or held by a talk. */
@Composable
private fun PhaseLine(phase: MusicPhase, clientName: String?) {
    val text = when (phase) {
        MusicPhase.LOADING -> "Loading…"
        MusicPhase.WAITING_CLIENT -> "Waiting for ${clientName ?: "passenger"}…"
        MusicPhase.PAUSED_FOR_TALK -> "Paused for talk — plays when the talk ends"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (phase != MusicPhase.PAUSED_FOR_TALK) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Waiting,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
