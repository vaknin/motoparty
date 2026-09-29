package com.kivan.motoparty

import android.app.Application

class MotopartyApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var history: HistoryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        history = HistoryStore(this)
    }

    companion object {
        lateinit var instance: MotopartyApp
            private set
    }
}
