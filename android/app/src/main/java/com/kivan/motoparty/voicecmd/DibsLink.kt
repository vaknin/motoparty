package com.kivan.motoparty.voicecmd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.kivan.motoparty.core.RideSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID

/**
 * The link to the dibs phone app on this phone (DIBS.md "The link"): a bound service with a
 * [Messenger] each way. We send the rider's messages and whether a ride is on; dibs sends back the
 * short spoken form of its new chat lines. dibs checks who is calling and asks the user once, on
 * its own screen, before it takes anything from this app. Main only: every reply arrives on Main.
 */
class DibsLink(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onLine: (RideSpeech.Line) -> Unit,
) {
    enum class State {
        /** No dibs app with the ride service on this phone. */
        ABSENT,
        CONNECTING,
        /** dibs is there but has not been allowed to take this app's messages (it asks on its screen). */
        NOT_ALLOWED,
        READY,
    }

    sealed interface Result {
        data object Sent : Result
        data class Refused(val why: String) : Result
        data object Unreachable : Result
    }

    private val _state = MutableStateFlow(State.ABSENT)
    val state: StateFlow<State> = _state

    private var service: Messenger? = null
    private var bound = false
    private val waiting = HashMap<String, CompletableDeferred<Result>>()

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg -> onReply(msg); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            log("dibs link: connected to ${name.packageName}")
            send(HELLO, Bundle().apply { putInt(K_VERSION, VERSION); putBoolean(K_RIDING, true) })
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.value = State.CONNECTING
            log("dibs link: disconnected (dibs will reconnect it)")
            failWaiting()
        }

        override fun onBindingDied(name: ComponentName) {
            log("dibs link: binding died, binding again")
            unbind()
            start()
        }

        override fun onNullBinding(name: ComponentName) {
            log("dibs link: ${name.packageName} gave no ride service")
            unbind()
            _state.value = State.ABSENT
        }
    }

    /** The signing certificates each dibs package had the first time; another is not dibs. */
    private val pins = context.getSharedPreferences("dibs-link", Context.MODE_PRIVATE)

    /** The ride service starts: bind to dibs, newest package first. Quietly absent without it. */
    fun start() {
        if (bound) return
        for (pkg in PACKAGES) {
            val intent = Intent(ACTION).setPackage(pkg)
            if (context.packageManager.resolveService(intent, 0) == null) continue
            if (!trusted(pkg)) continue
            bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (bound) {
                _state.value = State.CONNECTING
                log("dibs link: binding to $pkg")
                return
            }
            // A refused bind still has to be let go of before the connection is used again.
            runCatching { context.unbindService(connection) }
        }
        log("dibs link: no dibs app with the ride service")
        _state.value = State.ABSENT
    }

    /**
     * Is [pkg] signed as it was the first time we bound to it? The rider's words go there and its
     * lines are spoken in the helmet, so an app that only took the name is refused (DIBS.md "The
     * link"). The first sight is trusted: dibs is the user's own app, installed by dibs.
     */
    private fun trusted(pkg: String): Boolean {
        val digest = runCatching { signers(pkg) }.getOrNull() ?: return false
        val pinned = pins.getString(pkg, null)
        if (pinned == null) {
            pins.edit().putString(pkg, digest).apply()
            log("dibs link: $pkg pinned at first sight")
            return true
        }
        if (pinned == digest) return true
        log("dibs link: $pkg is signed by another key than before, refused")
        return false
    }

    private fun signers(pkg: String): String? {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = info.signingInfo?.apkContentsSigners ?: return null
        val sha = MessageDigest.getInstance("SHA-256")
        return certs.map { c -> sha.digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted().joinToString(",")
    }

    /** The ride service stops: tell dibs the ride is over, then let go. */
    fun stop() {
        send(BYE, Bundle().apply { putBoolean(K_RIDING, false) })
        unbind()
        _state.value = State.ABSENT
    }

    /** Send the rider's [text] to dibs as a spoken message from a ride; waits at most [TIMEOUT_MS]. */
    suspend fun say(text: String): Result {
        if (_state.value != State.READY) {
            return if (_state.value == State.NOT_ALLOWED) Result.Refused("not allowed yet") else Result.Unreachable
        }
        val uid = "ride-" + UUID.randomUUID()
        val answer = CompletableDeferred<Result>()
        waiting[uid] = answer
        if (!send(SAY, Bundle().apply { putString(K_UID, uid); putString(K_TEXT, text) })) {
            waiting.remove(uid)
            return Result.Unreachable
        }
        return withTimeoutOrNull(TIMEOUT_MS) { answer.await() }.also { waiting.remove(uid) } ?: Result.Unreachable
    }

    private fun send(what: Int, data: Bundle): Boolean {
        val s = service ?: return false
        return try {
            s.send(Message.obtain(null, what).apply { this.data = data; replyTo = replies })
            true
        } catch (e: RemoteException) {
            log("dibs link: send failed: ${e.message}")
            false
        }
    }

    private fun onReply(msg: Message) {
        val d = msg.data
        when (msg.what) {
            HELLO_OK -> {
                val ok = d.getBoolean(K_OK)
                _state.value = if (ok) State.READY else State.NOT_ALLOWED
                log("dibs link: ${if (ok) "ready" else "not allowed yet (${d.getString(K_WHY)})"}")
            }
            SAID -> {
                val uid = d.getString(K_UID) ?: return
                val r = if (d.getBoolean(K_OK)) Result.Sent else Result.Refused(d.getString(K_WHY) ?: "refused")
                waiting.remove(uid)?.complete(r)
            }
            LINE -> {
                val id = d.getString(K_ID) ?: return
                val speak = d.getString(K_SPEAK) ?: return
                onLine(RideSpeech.Line(id, speak, question = d.getBoolean(K_QUESTION)))
            }
            else -> log("dibs link: unknown reply ${msg.what}")
        }
    }

    private fun failWaiting() {
        for (w in waiting.values) w.complete(Result.Unreachable)
        waiting.clear()
    }

    private fun unbind() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
        failWaiting()
    }

    companion object {
        /** DIBS.md "The link": the whole contract, numbers included. */
        const val ACTION = "com.kivan.dibs.action.RIDE"
        /** The dibs app's own package first; Tether while dibs still lives inside it. */
        val PACKAGES = listOf("com.kivan.dibs", "com.kivan.tether")
        const val VERSION = 1
        const val TIMEOUT_MS = 5_000L

        // To dibs.
        const val HELLO = 1
        const val SAY = 2
        const val BYE = 3
        // From dibs.
        const val HELLO_OK = 101
        const val SAID = 102
        const val LINE = 103

        const val K_VERSION = "version"
        const val K_RIDING = "riding"
        const val K_UID = "uid"
        const val K_TEXT = "text"
        const val K_OK = "ok"
        const val K_WHY = "why"
        const val K_ID = "id"
        const val K_SPEAK = "speak"
        const val K_QUESTION = "question"
    }
}
