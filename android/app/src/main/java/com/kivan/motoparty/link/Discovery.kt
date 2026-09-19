package com.kivan.motoparty.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bonjour advert `_motoparty._tcp` with TXT proto/voice/http (PROTOCOL.md "Discovery"). */
class Discovery(context: Context, private val instanceName: String) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _registeredName = MutableStateFlow<String?>(null)
    /** The name NSD actually registered (it may add a suffix on a collision), or null. */
    val registeredName: StateFlow<String?> = _registeredName
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = SERVICE_TYPE
            port = ControlServer.PORT
            setAttribute("proto", "1")
            setAttribute("voice", VoiceSocket.PORT.toString())
            setAttribute("http", HTTP_PORT.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered ${info.serviceName}")
                _registeredName.value = info.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
                _registeredName.value = null
                listener = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _registeredName.value = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_motoparty._tcp"
        const val HTTP_PORT = 47802
        private const val TAG = "Discovery"
    }
}
