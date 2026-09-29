package com.kivan.motoparty

import android.content.Context
import com.kivan.motoparty.music.History
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [History], persisted in SharedPreferences like [SettingsStore] and observable. Main thread. */
class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(History.decode(prefs.getString(KEY, null)))
    val flow: StateFlow<History> = _flow
    val value: History get() = _flow.value

    fun update(change: (History) -> History) {
        val h = change(_flow.value)
        if (h == _flow.value) return
        _flow.value = h
        prefs.edit().putString(KEY, History.encode(h)).apply()
    }

    private companion object {
        const val KEY = "history"
    }
}
