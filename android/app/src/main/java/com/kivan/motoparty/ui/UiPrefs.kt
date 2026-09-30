package com.kivan.motoparty.ui

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What only the screens care about, kept apart from [com.kivan.motoparty.Settings] (which the
 * host reads): nothing here changes what the link, the music or talk do.
 */
@Immutable
data class UiPrefs(
    /**
     * The screen stays on while the Ride tab is showing and the host runs. Off by default: there
     * is no charger on the bike, and the floating button and the notification work with it off.
     */
    val keepScreenOn: Boolean = false,
    /** The Developer section of Settings was unlocked (seven taps on the version). */
    val developer: Boolean = false,
)

class UiPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(
        UiPrefs(
            keepScreenOn = prefs.getBoolean("keepScreenOn", false),
            developer = prefs.getBoolean("developer", false),
        ),
    )
    val flow: StateFlow<UiPrefs> = _flow

    fun update(change: (UiPrefs) -> UiPrefs) {
        val p = change(_flow.value)
        prefs.edit().putBoolean("keepScreenOn", p.keepScreenOn).putBoolean("developer", p.developer).apply()
        _flow.value = p
    }
}
