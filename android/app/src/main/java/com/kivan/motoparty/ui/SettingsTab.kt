package com.kivan.motoparty.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.Settings
import com.kivan.motoparty.UiAction

@Composable
fun SettingsTab(s: LinkStatus, settings: Settings, cb: Callbacks, modifier: Modifier = Modifier) {
    val update = cb.onSettings
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Group("Headset buttons") {
            SwitchRow("Play / pause starts a talk", "Off: it plays and pauses the music", settings.headsetPlayPause == "talk") { v ->
                update { it.copy(headsetPlayPause = if (v) "talk" else "music") }
            }
        }
        Group("On screen") {
            SwitchRow("Floating TALK button", "Shown over other apps, like maps", settings.overlayEnabled) { v ->
                update { it.copy(overlayEnabled = v) }
            }
        }
        Group("Voice") {
            var lang by remember(settings.asrLanguage) { mutableStateOf(settings.asrLanguage) }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(lang, { lang = it }, Modifier.weight(1f), label = { Text("Speech language (e.g. en-US)") }, singleLine = true)
                TextButton(
                    onClick = { update { it.copy(asrLanguage = lang.trim().ifEmpty { "en-US" }) } },
                    enabled = lang.trim() != settings.asrLanguage,
                ) { Text("Save") }
            }
        }
        Group("Sync") {
            Stepper("Resume after talk", "Head start for the headset to switch back", settings.resumeLeadMs, 250) { v ->
                update { it.copy(resumeLeadMs = v) }
            }
            Stepper("Latency trim", "Bluetooth delay of this phone's headset", settings.latencyTrimMs, 10) { v ->
                update { it.copy(latencyTrimMs = v) }
            }
            SwitchRow("Duck music during talk", "Off: music pauses while you talk", settings.duckDuringTalk) { v ->
                update { it.copy(duckDuringTalk = v) }
            }
        }
        Diagnostics(s, settings, cb)
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun Stepper(title: String, detail: String, value: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onChange(value - step) }) { Text("−", fontSize = 20.sp) }
        Text("$value ms", Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        TextButton(onClick = { onChange(value + step) }) { Text("+", fontSize = 20.sp) }
    }
}

/** Numbers and tools for testing, folded away by default. */
@Composable
private fun Diagnostics(s: LinkStatus, settings: Settings, cb: Callbacks) {
    var open by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Diagnostics", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(Icons.ChevronDown, if (open) "Hide" else "Show", Modifier.rotate(angle))
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyValue("Service", if (s.running) "running" else "stopped")
                    KeyValue("Bonjour", s.nsdName ?: "not registered")
                    KeyValue("Client", s.clientName?.let { "$it (${s.clientAddress})" } ?: "none")
                    KeyValue("Clock skew", s.clientSkewMs?.let { "$it ms (client − host, incl. one-way delay)" } ?: "–")
                    KeyValue("Last frame", s.lastPingAgeMs?.let { "$it ms ago" } ?: "–")
                    KeyValue("Talk", if (s.talkOpen) "OPEN" else "closed")
                    KeyValue("Jitter target", "${s.jitterTargetMs} ms, ${s.underruns} underruns")
                    KeyValue("UDP in/out", "${s.udpIn} / ${s.udpOut}")
                    KeyValue("Music drift", s.lastDriftMs?.let { "$it ms" } ?: "–")
                    KeyValue("Track cache", "${s.cacheMb} MB")
                    KeyValue("Call device", s.audioDevice ?: "–")
                    KeyValue("Audio devices", s.audioDevices ?: "–")
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Record talk mic to WAV (files/captures), also in a solo talk", Modifier.weight(1f).padding(end = 8.dp))
                        Switch(settings.captureDump, { v -> cb.onSettings { it.copy(captureDump = v) } })
                    }
                    OutlinedButton(onClick = { cb.onAction(UiAction.UsbStereoProbe) }, Modifier.fillMaxWidth()) {
                        Text("USB stereo test (Lark receiver, 3 × 15 s)")
                    }
                    OutlinedButton(onClick = { cb.onAction(UiAction.LongRecording) }, Modifier.fillMaxWidth()) {
                        Text(if (s.longRecording) "Stop long Lark recording" else "Start long Lark recording (until stopped)")
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Log", style = MaterialTheme.typography.labelLarge)
                    s.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(key, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
