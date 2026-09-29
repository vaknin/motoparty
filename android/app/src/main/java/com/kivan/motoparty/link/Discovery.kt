package com.kivan.motoparty.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bonjour advert `_motoparty._tcp` with TXT proto/voice/http (PROTOCOL.md "Discovery").
 *
 * A failed registration is retried with backoff ([RETRY_MS]: 2 s, 5 s, 10 s, then every 30 s) for
 * as long as the advert is started, so the host never silently stops advertising. Every
 * registration, unregistration and failure goes to [log] (the app's log).
 */
class Discovery(
    context: Context,
    private val instanceName: String,
    private val log: (String) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _registeredName = MutableStateFlow<String?>(null)
    /** The name NSD actually registered (it may add a suffix on a collision), or null. */
    val registeredName: StateFlow<String?> = _registeredName
    // All state below is touched on the main thread only (NSD callbacks are hopped onto it).
    private var listener: NsdManager.RegistrationListener? = null
    private var started = false
    private var failures = 0
    private val retry = Runnable { if (started && listener == null) register() }

    fun start() = onMain {
        if (started) return@onMain
        started = true
        failures = 0
        register()
    }

    fun stop() = onMain {
        started = false
        main.removeCallbacks(retry)
        listener?.let {
            runCatching { nsd.unregisterService(it) }
                .onFailure { e -> log("nsd: unregister failed: $e") }
        }
        listener = null
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = SERVICE_TYPE
            port = ControlServer.PORT
            setAttribute("proto", "1")
            setAttribute("voice", VoiceSocket.PORT.toString())
            setAttribute("http", HTTP_PORT.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = onMain {
                if (listener !== this) return@onMain
                failures = 0
                log("nsd: registered \"${info.serviceName}\"")
                _registeredName.value = info.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = onMain {
                if (listener !== this) return@onMain
                listener = null
                _registeredName.value = null
                if (!started) return@onMain
                val wait = RETRY_MS[minOf(failures, RETRY_MS.lastIndex)]
                failures++
                log("nsd: registration failed ($errorCode), retry in ${wait / 1000} s")
                main.postDelayed(retry, wait)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = onMain {
                log("nsd: unregistered \"${info.serviceName}\"")
                _registeredName.value = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = onMain {
                log("nsd: unregistration failed ($errorCode)")
            }
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            // e.g. "listener already in use"; treat it like a failed registration.
            log("nsd: register threw: $e")
            l.onRegistrationFailed(info, -1)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        const val SERVICE_TYPE = "_motoparty._tcp"
        const val HTTP_PORT = 47802
        /** Waits before re-registering after the 1st, 2nd, 3rd, and every later failure. */
        private val RETRY_MS = longArrayOf(2_000, 5_000, 10_000, 30_000)
    }
}
