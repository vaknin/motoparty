package com.kivan.motoparty.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.motoparty.Hub
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.MotopartyApp
import com.kivan.motoparty.Settings
import com.kivan.motoparty.UiAction
import com.kivan.motoparty.music.History
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers

data class Permission(val label: String, val granted: Boolean, val key: String)

enum class Tab(val label: String, val icon: ImageVector) {
    RIDE("Ride", Icons.Headset),
    SEARCH("Search", Icons.Search),
    QUEUE("Queue", Icons.Queue),
    SETTINGS("Settings", Icons.Tune),
}

/** What the screens can ask for. One object so previews and tests can pass no-ops. */
class Callbacks(
    val onGrant: (Permission) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onTrigger: (TriggerKind) -> Unit = {},
    val onAction: (UiAction) -> Unit = {},
    val onSettings: ((Settings) -> Settings) -> Unit = {},
    val onHistory: ((History) -> History) -> Unit = {},
)

@Composable
fun MainScreen(
    permissions: List<Permission>,
    onGrant: (Permission) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val status by Hub.status.collectAsStateWithLifecycle()
    val store = MotopartyApp.instance.settings
    val settings by store.flow.collectAsStateWithLifecycle()
    val historyStore = MotopartyApp.instance.history
    val history by historyStore.flow.collectAsStateWithLifecycle()
    Motoparty(
        status, settings, permissions,
        Callbacks(
            onGrant = onGrant,
            onStart = onStart,
            onStop = onStop,
            onTrigger = { Triggers.fire(it, TriggerSource.UI) },
            onAction = { Hub.actions.tryEmit(it) },
            onSettings = store::update,
            onHistory = historyStore::update,
        ),
        history = history,
    )
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
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            val count = if (t == Tab.QUEUE) status.queue.size else 0
                            BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else "$count") } }) {
                                Icon(t.icon, null)
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.RIDE -> RideTab(status, permissions, callbacks, onOpenQueue = { tab = Tab.QUEUE }, modifier = m)
            Tab.SEARCH -> SearchTab(status, callbacks, m, history)
            Tab.QUEUE -> QueueTab(status, callbacks, onSearch = { tab = Tab.SEARCH }, modifier = m)
            Tab.SETTINGS -> SettingsTab(status, settings, callbacks, m)
        }
    }
}
