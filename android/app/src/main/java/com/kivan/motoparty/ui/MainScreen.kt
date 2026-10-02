package com.kivan.motoparty.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.motoparty.BuildConfig
import com.kivan.motoparty.Diagnostics
import com.kivan.motoparty.Hub
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.MotopartyApp
import com.kivan.motoparty.Settings
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.core.ControlAction
import com.kivan.motoparty.music.History
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Permission(val label: String, val granted: Boolean, val key: String)

enum class Tab(val label: String, val icon: ImageVector) {
    RIDE("Ride", Icons.Headset),
    SEARCH("Search", Icons.Search),
    QUEUE("Queue", Icons.Queue),
    SETTINGS("Settings", Icons.Tune),
}

/** What the screens can ask for. One object so previews and tests can pass no-ops. */
@Stable
class Callbacks(
    val onGrant: (Permission) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** The "Microphone off" warning was tapped: claim the microphone again ([LinkStatus.micOff]). */
    val onRestoreMic: () -> Unit = {},
    val onTrigger: (TriggerKind) -> Unit = {},
    val onAction: (UiAction) -> Unit = {},
    val onSettings: ((Settings) -> Settings) -> Unit = {},
    val onHistory: ((History) -> History) -> Unit = {},
    val onUiPrefs: ((UiPrefs) -> UiPrefs) -> Unit = {},
) {
    fun withAction(onAction: (UiAction) -> Unit) =
        Callbacks(onGrant, onStart, onStop, onRestoreMic, onTrigger, onAction, onSettings, onHistory, onUiPrefs)
}

/**
 * The once-a-second numbers and the log, as flows: only the Developer section of Settings collects
 * them, and only while it is open, so they redraw nothing else (UA4).
 */
@Stable
class DevFlows(val diagnostics: StateFlow<Diagnostics>, val log: StateFlow<List<String>>) {
    companion object {
        val Live = DevFlows(Hub.diagnostics, Hub.logLines)
    }
}

@Composable
fun MainScreen(
    permissions: List<Permission>,
    onGrant: (Permission) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestoreMic: () -> Unit,
) {
    val status by Hub.status.collectAsStateWithLifecycle()
    val store = MotopartyApp.instance.settings
    val settings by store.flow.collectAsStateWithLifecycle()
    val historyStore = MotopartyApp.instance.history
    val history by historyStore.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val prefsStore = remember { UiPrefsStore(context) }
    val prefs by prefsStore.flow.collectAsStateWithLifecycle()
    val callbacks = remember(onGrant, onStart, onStop, onRestoreMic) {
        Callbacks(
            onGrant = onGrant,
            onStart = onStart,
            onStop = onStop,
            onRestoreMic = onRestoreMic,
            onTrigger = { Triggers.fire(it, TriggerSource.UI) },
            onAction = { Hub.actions.tryEmit(it) },
            onSettings = store::update,
            onHistory = historyStore::update,
            onUiPrefs = prefsStore::update,
        )
    }
    Motoparty(status, settings, permissions, callbacks, history = history, prefs = prefs)
}

/** The whole app, from state alone. */
@Composable
fun Motoparty(
    status: LinkStatus,
    settings: Settings,
    permissions: List<Permission>,
    callbacks: Callbacks,
    initialTab: Tab = Tab.RIDE,
    history: History = History(),
    prefs: UiPrefs = UiPrefs(),
    dev: DevFlows = DevFlows.Live,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Back from any other tab is the Ride tab, not the launcher (UA8). An open album on the
    // Search tab has its own handler, composed later, so it wins.
    BackHandler(enabled = tab != Tab.RIDE) { tab = Tab.RIDE }

    // Confirmations: a snackbar says what a tap did, with Undo where there is one.
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(status)
    val cb = remember(callbacks) {
        callbacks.withAction { action ->
            val said = confirmation(action, current)
            callbacks.onAction(action)
            // The Ride screen's artist or album tap (2026-10-02): the page opens on the Search tab.
            if (opensSearch(action)) tab = Tab.SEARCH
            if (said != null) scope.launch {
                snackbars.currentSnackbarData?.dismiss()
                val result = snackbars.showSnackbar(
                    said.text,
                    actionLabel = if (said.undo != null) "Undo" else null,
                    duration = if (said.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) said.undo?.let(callbacks.onAction)
            }
        }
    }

    if (prefs.keepScreenOn && status.running && tab == Tab.RIDE) KeepScreenOn()

    val queued = status.queue.size
    val now = status.nowPlaying
    val mini: @Composable () -> Unit = {
        if (tab != Tab.RIDE && now != null) {
            MiniPlayer(
                now, status.playing, status.anchor,
                onOpen = { tab = Tab.RIDE },
                onPlayPause = { cb.onAction(UiAction.Control(if (status.playing) ControlAction.PAUSE else ControlAction.RESUME)) },
            )
        }
    }
    val screen: @Composable (Modifier) -> Unit = { m ->
        when (tab) {
            Tab.RIDE -> RideTab(
                status, permissions, cb, onOpenQueue = { tab = Tab.QUEUE }, modifier = m,
                // The host interprets: the smart-command hints are true.
                smart = settings.smartCommands && BuildConfig.GEMINI_API_KEY.isNotEmpty(),
                lyrics = settings.lyrics,
                prefs = prefs,
            )
            Tab.SEARCH -> SearchTab(status, cb, m, history)
            Tab.QUEUE -> QueueTab(status, cb, onSearch = { tab = Tab.SEARCH }, modifier = m)
            Tab.SETTINGS -> SettingsTab(status, settings, cb, m, prefs, dev)
        }
    }
    val tabIcon: @Composable (Tab) -> Unit = { t ->
        val count = if (t == Tab.QUEUE) queued else 0
        BadgedBox(
            badge = {
                if (count > 0) {
                    // The brand colour, not error red: a queue is not a problem (UA12).
                    Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text(badgeText(count))
                    }
                }
            },
        ) { Icon(t.icon, null) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            // On its side (the handlebar mount): the tabs go to a rail, so the height stays for the screen.
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.weight(1f))
                    for (t in Tab.entries) {
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { tabIcon(t) },
                            label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbars) },
                    bottomBar = mini,
                ) { padding -> screen(Modifier.padding(padding)) }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbars) },
                bottomBar = {
                    Column {
                        mini()
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            for (t in Tab.entries) {
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { tabIcon(t) },
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding -> screen(Modifier.padding(padding)) }
        }
    }
}

/** The window keeps the screen on for as long as this is in the composition. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
