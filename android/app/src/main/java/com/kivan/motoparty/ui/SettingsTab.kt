package com.kivan.motoparty.ui

import android.content.pm.ApplicationInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.Settings
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.music.LatencyTrims
import kotlinx.coroutines.delay

/**
 * Three layers: what a rider sets (the floating button, the screen, the microphone, the
 * language), Tuning (the two timings), and Developer — the numbers, the recordings and the log —
 * which only shows in a debug build or after seven taps on the version.
 */
@Composable
fun SettingsTab(
    s: LinkStatus,
    settings: Settings,
    cb: Callbacks,
    modifier: Modifier = Modifier,
    prefs: UiPrefs = UiPrefs(),
    dev: DevFlows = DevFlows.Live,
) {
    val update = cb.onSettings
    val context = LocalContext.current
    val debugBuild = remember { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "–"
    }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Group("On the road") {
            SwitchRow("Floating TALK button", "Shown over other apps, like maps", settings.overlayEnabled) { v ->
                update { it.copy(overlayEnabled = v) }
            }
            SwitchRow(
                "Keep screen on while riding",
                "While the Ride tab is showing. Uses more battery",
                prefs.keepScreenOn,
            ) { v -> cb.onUiPrefs { it.copy(keepScreenOn = v) } }
        }
        Group("Voice") {
            SwitchRow(
                "Use the Lark USB mic for talk",
                "With the receiver plugged in: rider on the pink transmitter, passenger on the yellow. Off: earbud mics",
                settings.larkTalk,
            ) { v -> update { it.copy(larkTalk = v) } }
            SwitchRow("Swap rider and passenger", "If the two Lark transmitters are the wrong way round", settings.larkSwap) { v ->
                update { it.copy(larkSwap = v) }
            }
            LanguageRow(settings.asrLanguage) { tag -> update { it.copy(asrLanguage = tag) } }
        }
        Group("Tuning") {
            Stepper(
                "Resume after talk",
                "Head start for the headset",
                settings.resumeLeadMs,
                step = 250,
                range = 0..5000,
            ) { v -> update { it.copy(resumeLeadMs = v) } }
            val route = s.outputRoute
            if (route != null) {
                // The offset is per output: each Bluetooth device has its own, the phone's speaker
                // and wired outputs share one. This row edits the one in use right now.
                Stepper(
                    "Music sync offset",
                    "${route.name}: raise if this phone plays late",
                    settings.trims.of(route),
                    step = 10,
                    range = LatencyTrims.MIN_MS..LatencyTrims.MAX_MS,
                ) { v -> update { it.copy(trims = it.trims.with(route, v)) } }
            } else {
                Text(
                    "Music sync offset: shown for the current output once Motoparty is running.",
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Group("Motoparty") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val line = LinkLine.of(s)
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(if (s.running) "Running" else "Stopped", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (s.running) line.title else "No link, no music, no talk",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.running) {
                    OutlinedButton(onClick = { confirmStop = true }, Modifier.height(52.dp)) { Text("Stop") }
                } else {
                    Button(onClick = cb.onStart, Modifier.height(52.dp)) { Text("Start") }
                }
            }
            var taps by remember { mutableIntStateOf(0) }
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClickLabel = "Version") {
                        if (++taps >= DEVELOPER_TAPS && !prefs.developer) cb.onUiPrefs { it.copy(developer = true) }
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Version", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(version, style = MaterialTheme.typography.bodyLarge.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (debugBuild || prefs.developer) Developer(s, settings, cb, dev)
    }
    if (confirmStop) StopDialog(onDismiss = { confirmStop = false }, onStop = cb.onStop)
}

private const val DEVELOPER_TAPS = 7

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onChange)
    }
}

/** The recogniser's language, picked by name from a list instead of typed as a tag (UA13: nothing unsaved to lose). */
@Composable
private fun LanguageRow(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Speech language", style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageName(current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.ChevronDown, "Choose", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (tag in speechLanguages(current)) {
                DropdownMenuItem(
                    text = {
                        Text(
                            languageName(tag),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (tag == current) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (tag == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; onPick(tag) },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
}

/** A number with − and +, 56 dp each; holding one repeats. */
@Composable
private fun Stepper(title: String, detail: String, value: Int, step: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RepeatButton(Icons.Remove, "Less", enabled = value > range.first) { onChange((value - step).coerceIn(range)) }
        Text(
            "$value ms",
            Modifier.width(76.dp),
            style = MaterialTheme.typography.bodyLarge.tabular,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        RepeatButton(Icons.Add, "More", enabled = value < range.last) { onChange((value + step).coerceIn(range)) }
    }
}

/** A tap is one step; held for [REPEAT_AFTER_MS] it steps every [REPEAT_EVERY_MS] until let go. */
@Composable
private fun RepeatButton(icon: ImageVector, label: String, enabled: Boolean, onStep: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    val on by rememberUpdatedState(enabled)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        repeated = false
        delay(REPEAT_AFTER_MS)
        while (on) {
            repeated = true
            step()
            delay(REPEAT_EVERY_MS)
        }
    }
    FilledTonalIconButton(
        // The release after a hold is not one more step.
        onClick = { if (!repeated) step() },
        modifier = Modifier.size(56.dp),
        enabled = enabled,
        interactionSource = interaction,
    ) { Icon(icon, label) }
}

private const val REPEAT_AFTER_MS = 450L
private const val REPEAT_EVERY_MS = 90L

/** Numbers and tools for testing, folded away by default. The 1 Hz numbers and the log are read only while it is open. */
@Composable
private fun Developer(s: LinkStatus, settings: Settings, cb: Callbacks, dev: DevFlows) {
    var open by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { open = !open }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Developer", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.ChevronDown, if (open) "Hide" else "Show", Modifier.rotate(angle))
            }
            AnimatedVisibility(open) { DeveloperBody(s, settings, cb, dev) }
        }
    }
}

@Composable
private fun DeveloperBody(s: LinkStatus, settings: Settings, cb: Callbacks, dev: DevFlows) {
    val d by dev.diagnostics.collectAsStateWithLifecycle()
    val log by dev.log.collectAsStateWithLifecycle()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KeyValue("Service", if (s.running) "running" else "stopped")
        KeyValue("Bonjour", s.nsdName ?: "not registered")
        KeyValue("Client", s.clientName?.let { "$it (${d.clientAddress})" } ?: "none")
        KeyValue("Clock skew", d.clientSkewMs?.let { "$it ms (client − host, incl. one-way delay)" } ?: "–")
        KeyValue("Last frame", d.lastPingAgeMs?.let { "$it ms ago" } ?: "–")
        KeyValue("Talk", if (s.talkOpen) (if (s.talkLive) "OPEN, live" else "OPEN, connecting") else "closed")
        KeyValue("Jitter target", "${d.jitterTargetMs} ms, ${d.underruns} underruns")
        KeyValue("UDP in/out", "${d.udpIn} / ${d.udpOut}")
        KeyValue("Music drift", d.lastDriftMs?.let { "$it ms" } ?: "–")
        KeyValue("Track cache", "${d.cacheMb} MB")
        KeyValue("Call device", d.audioDevice ?: "–")
        KeyValue("Audio devices", d.audioDevices ?: "–")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Record talk mic to WAV (files/captures), also in a solo talk", Modifier.weight(1f).padding(end = 8.dp))
            Switch(settings.captureDump, { v -> cb.onSettings { it.copy(captureDump = v) } })
        }
        OutlinedButton(onClick = { cb.onAction(UiAction.UsbStereoProbe) }, Modifier.fillMaxWidth().height(52.dp)) {
            Text("USB stereo test (Lark receiver, 3 × 15 s)")
        }
        OutlinedButton(onClick = { cb.onAction(UiAction.LongRecording) }, Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (s.longRecording) "Stop long Lark recording" else "Start long Lark recording (until stopped)")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Log", style = MaterialTheme.typography.labelLarge)
        log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(key, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall.tabular)
    }
}
