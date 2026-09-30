package com.kivan.motoparty.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.music.Track
import com.kivan.motoparty.trigger.TriggerKind

/**
 * The screen for the road. Nothing scrolls: the link on top, what is playing in the middle, and
 * the one big button where the thumb is — at the bottom upright, the whole right side on a
 * handlebar mount. Stopping the host is a long press on the link line (or Settings), so a glove
 * brushing the screen cannot end the ride's music (UA6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideTab(
    s: LinkStatus,
    permissions: List<Permission>,
    cb: Callbacks,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = TalkPhase.of(s)
    var commandsSheet by rememberSaveable { mutableStateOf(false) }
    var permissionsSheet by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val missing = permissions.filter { !it.granted }
    // While a phrase can still be a command, the list of them takes the place of the (paused) music.
    val showCommands = s.talkOpen && s.commandWindow

    val link: @Composable () -> Unit = { LinkHeader(s, onLongPress = { if (s.running) confirmStop = true }) }
    val middle: @Composable (Modifier) -> Unit = { m ->
        if (showCommands) CommandsCard(solo = s.clientName == null, modifier = m) else NowPlaying(s, cb, onOpenQueue, m)
    }
    val hint: @Composable () -> Unit = { SayLine(s.heard.takeIf { s.talkOpen }) { commandsSheet = true } }
    val talk: @Composable (Modifier) -> Unit = { m ->
        if (s.running) TalkButton(phase, m) { cb.onTrigger(TriggerKind.TALK) } else StartButton(m, cb.onStart)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    link()
                    Warnings(s, missing, cb, atMost = 1) { permissionsSheet = true }
                    middle(Modifier.weight(1f).fillMaxWidth())
                    if (s.running && !showCommands) hint()
                }
                talk(Modifier.width((this@BoxWithConstraints.maxWidth * 0.36f).coerceIn(200.dp, 340.dp)).fillMaxHeight())
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                link()
                Warnings(s, missing, cb, atMost = 2) { permissionsSheet = true }
                middle(Modifier.weight(1f).fillMaxWidth())
                if (s.running && !showCommands) hint()
                talk(Modifier.fillMaxWidth().height(if (this@BoxWithConstraints.maxHeight < 640.dp) 144.dp else 168.dp))
            }
        }
    }

    if (commandsSheet) {
        ModalBottomSheet(
            onDismissRequest = { commandsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            CommandsList(solo = s.clientName == null, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding())
        }
    }
    if (permissionsSheet && missing.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { permissionsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            PermissionsList(missing, cb.onGrant, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding())
        }
    }
    if (confirmStop) StopDialog(onDismiss = { confirmStop = false }, onStop = cb.onStop)
}

/** "Stop Motoparty?": the one place the host is stopped from, here and in Settings. */
@Composable
fun StopDialog(onDismiss: () -> Unit, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop Motoparty?") },
        text = { Text("The link to the passenger, the music and talk all stop, on both phones.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onStop() }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep riding") } },
    )
}

// ---- the link ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkHeader(s: LinkStatus, onLongPress: () -> Unit) {
    val line = LinkLine.of(s)
    val dot = when (line.kind) {
        LinkLine.Kind.OFF -> Palette.Off
        LinkLine.Kind.WAITING -> Palette.Waiting
        LinkLine.Kind.CONNECTED -> Palette.Good
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(
                onLongClickLabel = "Stop Motoparty",
                onLongClick = onLongPress,
                // A tap does nothing on purpose; the long press is announced to TalkBack.
                onClick = {},
                indication = null,
                interactionSource = null,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(14.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(line.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                line.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What needs the rider's attention, most urgent first, at most [atMost] of them so the screen
 * never has to scroll: the microphone being off, missing permissions, the Lark receiver, the last
 * failure.
 */
@Composable
private fun Warnings(s: LinkStatus, missing: List<Permission>, cb: Callbacks, atMost: Int, onPermissions: () -> Unit) {
    val banners = buildList<@Composable () -> Unit> {
        if (s.running && s.micOff) add {
            Banner(
                Icons.MicOff,
                "Microphone off – tap to restore",
                Palette.TalkOpen,
                detail = "Talk does not work until it is back.",
                onClick = cb.onRestoreMic,
            )
        }
        if (missing.isNotEmpty()) add {
            Banner(
                Icons.Warning,
                if (missing.size == 1) "${missing[0].label}: permission needed" else "${missing.size} permissions needed",
                MaterialTheme.colorScheme.primary,
                detail = if (missing.size == 1) "Tap to allow" else missing.joinToString(", ") { it.label } + " — tap to allow",
                onClick = onPermissions,
            )
        }
        s.larkMissing?.let { reason ->
            // Talk silently falls back to the earbud mics otherwise. Red while a talk is on them.
            val inTalk = s.talkOnEarbudsFallback
            add {
                Banner(
                    Icons.Warning,
                    if (inTalk) "This talk is on the earbud mics" else "Lark receiver not detected",
                    if (inTalk) Palette.TalkOpen else Palette.Waiting,
                    detail = if (inTalk) "Lark receiver not detected ($reason). Replug it; the next talk will use it."
                    else "Talk will use the earbud mics ($reason). Replug the receiver.",
                )
            }
        }
        s.error?.let { text ->
            add { Banner(Icons.Warning, text, MaterialTheme.colorScheme.error, onDismiss = { cb.onAction(UiAction.DismissError) }) }
        }
    }
    for (b in banners.take(atMost)) b()
}

@Composable
private fun PermissionsList(missing: List<Permission>, onGrant: (Permission) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Motoparty needs a few permissions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Without them talk, the link or the floating button do not work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (p in missing) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
                FilledTonalButton(onClick = { onGrant(p) }, Modifier.height(52.dp)) { Text("Allow") }
            }
        }
    }
}

// ---- now playing ----

@Composable
private fun NowPlaying(s: LinkStatus, cb: Callbacks, onOpenQueue: () -> Unit, modifier: Modifier) {
    val t = s.nowPlaying
    BoxWithConstraints(modifier) {
        val h = maxHeight
        when {
            t == null -> NothingPlaying(s, compact = h < 260.dp)
            // Upright with room: the cover on top, as big as the space left over.
            h >= 400.dp -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val size = min(min(maxWidth, maxHeight), 260.dp)
                    Crossfade(t.art to t.id, label = "art") { (art, _) -> Art(art, size) }
                }
                Crossfade(t, label = "title") { track -> Titles(track, centred = true) }
                StatusLine(s)
                Timeline(s, t)
                Transport(s.playing, big = true) { cb.onAction(UiAction.Control(it)) }
                UpNext(s.queue, onOpenQueue)
            }
            // Tight (a banner or two, or the handlebar layout): the cover beside the title.
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BoxWithConstraints(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                        val size = min(maxHeight, 112.dp)
                        if (size >= 48.dp) Crossfade(t.art to t.id, label = "art") { (art, _) -> Art(art, size) }
                    }
                    Column(Modifier.weight(1f)) {
                        Crossfade(t, label = "title") { track -> Titles(track, centred = false) }
                        StatusLine(s)
                    }
                }
                Timeline(s, t)
                Transport(s.playing, big = h >= 300.dp) { cb.onAction(UiAction.Control(it)) }
                if (h >= 330.dp) UpNext(s.queue, onOpenQueue)
            }
        }
    }
}

@Composable
private fun NothingPlaying(s: LinkStatus, compact: Boolean) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (!compact) Art(null, 120.dp, Modifier.padding(bottom = 8.dp))
        Text("Nothing playing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (s.running) "Find something on the Search tab, or press TALK and say “play …”"
            else "Start Motoparty, then find something on the Search tab",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        StatusLine(s)
    }
}

@Composable
private fun Titles(t: Track, centred: Boolean) {
    val align = if (centred) TextAlign.Center else TextAlign.Start
    Column(
        if (centred) Modifier.fillMaxWidth() else Modifier,
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            t.title,
            style = if (centred) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
        )
        Text(
            t.artist,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
        )
    }
}

/**
 * One line under the title for whatever is going on: why the song is not playing yet, a search
 * that is running, or the last spoken reply (for a few seconds). Nothing when there is nothing.
 */
@Composable
private fun StatusLine(s: LinkStatus) {
    val phase = s.musicPhase
    val (text, spinner, color) = when {
        phase != null && s.nowPlaying != null ->
            Triple(phaseText(phase, s.clientName), phase != com.kivan.motoparty.music.MusicPhase.PAUSED_FOR_TALK, Palette.Waiting)
        s.busy != null -> Triple(s.busy + "…", true, Palette.Waiting)
        s.lastAnnounce != null -> Triple("“${s.lastAnnounce}”", false, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (spinner) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Timeline(s: LinkStatus, t: Track) {
    val now = rememberPlaybackClock(s.anchor)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrackProgress(s.anchor, now, t.durationMs, Modifier.fillMaxWidth().height(6.dp))
        Row {
            PositionText(s.anchor, now, t.durationMs)
            Spacer(Modifier.weight(1f))
            Text(
                if (t.durationMs > 0) mmss(t.durationMs) else "–:––",
                style = MaterialTheme.typography.bodyMedium.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Previous, play/pause, next: 72 / 96 / 72 dp, or 64 / 80 / 64 where the height is short. */
@Composable
private fun Transport(playing: Boolean, big: Boolean, control: (String) -> Unit) {
    val side = if (big) 72.dp else 64.dp
    val main = if (big) 96.dp else 76.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { control(ControlAction.PREVIOUS) }, Modifier.size(side)) {
            Icon(Icons.Previous, "Previous", Modifier.size(side * 0.6f))
        }
        FilledIconButton(
            onClick = { control(if (playing) ControlAction.PAUSE else ControlAction.RESUME) },
            modifier = Modifier.size(main),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Icon(
                if (playing) Icons.Pause else Icons.Play,
                if (playing) "Pause" else "Play",
                Modifier.size(main * 0.52f),
                tint = MaterialTheme.colorScheme.surface,
            )
        }
        IconButton(onClick = { control(ControlAction.NEXT) }, Modifier.size(side)) {
            Icon(Icons.Next, "Next", Modifier.size(side * 0.6f))
        }
    }
}

@Composable
private fun UpNext(queue: List<Track>, onOpenQueue: () -> Unit) {
    val next = queue.firstOrNull() ?: return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open the queue", onClick = onOpenQueue),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Queue, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Up next: ${next.title}",
            Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (queue.size > 1) {
            Text(
                "+${queue.size - 1}",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyLarge.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- spoken commands (PROTOCOL.md "Commands") ----

/** The one-line reminder that opens the list; during a talk, what the recogniser last heard. */
@Composable
private fun SayLine(heard: String?, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (heard != null) "Heard" else "Say",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (heard != null) "“$heard”" else COMMANDS_LINE,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (heard != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.ChevronUp, "All voice commands", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The command list in place of the music, while the talk's first phrase can still be one. */
@Composable
private fun CommandsCard(solo: Boolean, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        CommandsList(solo, Modifier.verticalScroll(rememberScrollState()).padding(16.dp), inTalk = true)
    }
}

/**
 * Every spoken command, readable at a glance: a command is the first phrase of a talk, and in a
 * solo talk every phrase is one. `<…>` is what the rider fills in, drawn lighter.
 */
@Composable
fun CommandsList(solo: Boolean, modifier: Modifier = Modifier, inTalk: Boolean = false) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (inTalk) "Say a command" else "Voice commands",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when {
                inTalk && solo -> "Alone, every phrase is a command."
                inTalk -> "Only the first thing you say. After that it's just talk."
                solo -> "Press TALK and say one. Alone, every phrase is a command."
                else -> "Press TALK and say one of these first. After that it's just talk."
            },
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
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- the one big button ----

/**
 * Glove-sized, and the same one action as the overlay and the notification: a press toggles talk.
 * Orange "TALK"; a pulsing ring and "Connecting…" while the headset switches; red "END TALK" with
 * a LIVE pill once the microphone is live; "Ending…" while it switches back. A tick of haptics on
 * the press and another when it goes live, for a thumb that cannot see the screen.
 */
@Composable
private fun TalkButton(phase: TalkPhase, modifier: Modifier, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var last by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        if (phase == TalkPhase.LIVE && last != TalkPhase.LIVE) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        last = phase
    }
    val surface = MaterialTheme.colorScheme.surface
    val (container, content) = when (phase) {
        TalkPhase.IDLE -> Palette.Talk to Palette.OnTalk
        TalkPhase.OPENING -> Palette.Talk.copy(alpha = 0.28f).compositeOver(surface) to MaterialTheme.colorScheme.onSurface
        TalkPhase.LIVE -> Palette.TalkOpen to Palette.OnTalkOpen
        TalkPhase.CLOSING -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ring = if (phase == TalkPhase.OPENING) {
        val pulse by rememberInfiniteTransition(label = "talk").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
            label = "ring",
        )
        BorderStroke(5.dp, Palette.Talk.copy(alpha = pulse))
    } else {
        null
    }
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        border = ring,
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            if (phase == TalkPhase.LIVE) LivePill()
            Icon(Icons.Mic, null, Modifier.size(44.dp))
            Text(phase.label, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (phase == TalkPhase.OPENING) {
                Text("Speak after the beep", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LivePill() {
    Row(
        Modifier.background(Color.White, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(Palette.TalkOpen, CircleShape))
        Text("LIVE", color = Color(0xFFB4232A), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
    }
}

/** The host is off: one button, where TALK would be. */
@Composable
private fun StartButton(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Palette.Talk,
        contentColor = Palette.OnTalk,
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Power, null, Modifier.size(40.dp))
            Text("Start Motoparty", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}
