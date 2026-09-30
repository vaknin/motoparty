package com.kivan.motoparty.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * Is there a network to download tracks over, and when does it come back: what
 * [MusicController] needs to keep a track that failed for lack of signal instead of skipping it.
 * Watches the default network (on the host that is the mobile one; the hotspot is not it).
 * [onBack] runs on the main thread.
 */
class NetworkWatch(context: Context, private val onBack: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    var online: Boolean = manager.activeNetwork != null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            online = true
            onBack()
        }

        override fun onLost(network: Network) {
            online = false
        }
    }

    init {
        manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun release() = manager.unregisterNetworkCallback(callback)
}
