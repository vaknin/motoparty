package com.kivan.motoparty.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.motoparty.Hub
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.MotopartyApp
import com.kivan.motoparty.Settings
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers

data class Permission(val label: String, val granted: Boolean, val key: String)

@Composable
fun MainScreen(
    permissions: List<Permission>,
    onGrant: (Permission) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val s by Hub.status.collectAsStateWithLifecycle()
    val settingsStore = MotopartyApp.instance.settings
    val settings by settingsStore.flow.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Motoparty host", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (s.running) OutlinedButton(onClick = onStop) { Text("Stop") } else Button(onClick = onStart) { Text("Start") }
            }
            if (permissions.any { !it.granted }) PermissionsCard(permissions, onGrant)
            TriggerButtons(s)
            LinkCard(s)
            NowPlayingCard(s)
            SearchCard(s)
            SettingsCard(settings) { change -> settingsStore.update(change) }
            LogCard(s.log)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(key, Modifier.width(130.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun PermissionsCard(permissions: List<Permission>, onGrant: (Permission) -> Unit) = Section("Permissions") {
    for (p in permissions) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, Modifier.weight(1f))
            if (p.granted) Text("granted", color = Color(0xFF81C784)) else TextButton(onClick = { onGrant(p) }) { Text("Grant") }
        }
    }
}

@Composable
private fun TriggerButtons(s: LinkStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { Triggers.fire(TriggerKind.TALK, TriggerSource.UI) },
            modifier = Modifier.weight(1f).height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (s.talkOpen) Color(0xFF2E7D32) else Color(0xFF1565C0)),
        ) { Text(if (s.talkOpen) "END TALK" else "TALK", fontSize = 22.sp) }
        Button(
            onClick = { Triggers.fire(TriggerKind.MUSIC, TriggerSource.UI) },
            modifier = Modifier.weight(1f).height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (s.listening) Color(0xFFC62828) else Color(0xFF6A1B9A)),
        ) { Text(if (s.listening) "LISTENING" else "COMMAND", fontSize = 22.sp) }
    }
}

@Composable
private fun LinkCard(s: LinkStatus) = Section("Link") {
    KeyValue("Service", if (s.running) "running" else "stopped")
    KeyValue("Bonjour", s.nsdName ?: "not registered")
    KeyValue("Client", s.clientName?.let { "$it (${s.clientAddress})" } ?: "none")
    KeyValue("Clock skew", s.clientSkewMs?.let { "$it ms (client − host, incl. one-way delay)" } ?: "–")
    KeyValue("Last frame", s.lastPingAgeMs?.let { "$it ms ago" } ?: "–")
    KeyValue("Talk", if (s.talkOpen) "OPEN" else "closed")
    KeyValue("Jitter target", "${s.jitterTargetMs} ms, ${s.underruns} underruns")
    KeyValue("UDP in/out", "${s.udpIn} / ${s.udpOut}")
    KeyValue("Call device", s.audioDevice ?: "–")
    KeyValue("Audio devices", s.audioDevices ?: "–")
}

private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
private fun NowPlayingCard(s: LinkStatus) = Section("Now playing") {
    val t = s.nowPlaying
    if (t == null) {
        Text("Nothing loaded")
    } else {
        Text("${t.title} — ${t.artist}", fontWeight = FontWeight.SemiBold)
        Text("${mmss(s.positionMs)} / ${mmss(t.durationMs)} · ${if (s.playing) "playing" else "paused"}" +
            (s.lastDriftMs?.let { " · drift $it ms" } ?: ""))
    }
    s.busy?.let { Text(it, color = Color(0xFFFFB74D)) }
    s.lastAnnounce?.let { Text("“$it”", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Hub.actions.tryEmit(UiAction.Control("previous")) }) { Text("⏮") }
        OutlinedButton(onClick = { Hub.actions.tryEmit(UiAction.Control(if (s.playing) "pause" else "resume")) }) {
            Text(if (s.playing) "⏸" else "▶")
        }
        OutlinedButton(onClick = { Hub.actions.tryEmit(UiAction.Control("next")) }) { Text("⏭") }
    }
    if (s.queue.isNotEmpty()) {
        Text("Up next (${s.queue.size})", fontWeight = FontWeight.SemiBold)
        s.queue.take(8).forEach { Text("· ${it.title} — ${it.artist}", maxLines = 1) }
    }
    Text("Cache ${s.cacheMb} MB", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchCard(s: LinkStatus) = Section("Search") {
    var query by remember { mutableStateOf("") }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Song, or a command like “play album …”") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (query.isNotBlank()) Hub.actions.tryEmit(UiAction.Search(query)) }) { Text("Search songs") }
        OutlinedButton(onClick = { if (query.isNotBlank()) Hub.actions.tryEmit(UiAction.Command(query)) }) { Text("Run as command") }
    }
    s.searchResults.forEachIndexed { i, t ->
        Text(
            "${t.title} — ${t.artist} (${mmss(t.durationMs)})",
            Modifier.fillMaxWidth().clickable { Hub.actions.tryEmit(UiAction.Play(s.searchResults, i)) }.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun SettingsCard(settings: Settings, update: ((Settings) -> Settings) -> Unit) = Section("Settings") {
    SwitchRow("Duck music during talk (instead of pause)", settings.duckDuringTalk) { v -> update { it.copy(duckDuringTalk = v) } }
    Stepper("Resume lead", settings.resumeLeadMs, 250) { v -> update { it.copy(resumeLeadMs = v) } }
    Stepper("Latency trim", settings.latencyTrimMs, 10) { v -> update { it.copy(latencyTrimMs = v) } }
    var lang by remember(settings.asrLanguage) { mutableStateOf(settings.asrLanguage) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(lang, { lang = it }, Modifier.weight(1f), label = { Text("ASR language (BCP-47)") }, singleLine = true)
        TextButton(onClick = { update { it.copy(asrLanguage = lang.trim().ifEmpty { "en-US" }) } }) { Text("Set") }
    }
    SwitchRow("Headset play/pause = talk (off: music play/pause)", settings.headsetPlayPause == "talk") { v ->
        update { it.copy(headsetPlayPause = if (v) "talk" else "music") }
    }
    SwitchRow("Headset next = voice command (off: next track)", settings.headsetNext == "command") { v ->
        update { it.copy(headsetNext = if (v) "command" else "next") }
    }
    SwitchRow("Floating TALK/MUSIC overlay", settings.overlayEnabled) { v -> update { it.copy(overlayEnabled = v) } }
    SwitchRow("Debug: record talk mic to WAV (files/captures). With no passenger connected, TALK records solo", settings.captureDump) { v ->
        update { it.copy(captureDump = v) }
    }
    OutlinedButton(onClick = { Hub.actions.tryEmit(UiAction.UsbStereoProbe) }) {
        Text("Debug: USB stereo test (Lark receiver, 3 × 15 s)")
    }
    val long = Hub.status.collectAsStateWithLifecycle().value.longRecording
    Button(onClick = { Hub.actions.tryEmit(UiAction.LongRecording) }) {
        Text(if (long) "Stop long Lark recording" else "Debug: start long Lark recording (until stopped)")
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange(value - step) }) { Text("−") }
        Text("$value ms", Modifier.width(80.dp))
        TextButton(onClick = { onChange(value + step) }) { Text("+") }
    }
}

@Composable
private fun LogCard(log: List<String>) = Section("Log") {
    log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
}
