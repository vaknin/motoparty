package com.kivan.motoparty

import android.app.Application

class MotopartyApp : Application() {
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
    }

    companion object {
        lateinit var instance: MotopartyApp
            private set
    }
}
