package com.kivan.motoparty.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.kivan.motoparty.link.CallController
import java.util.concurrent.Executor

/**
 * The phone's cellular call state and the caller, for [CallController] (2026-10-02). A thin
 * Android wrapper; every decision is the controller's.
 *
 * - **State**: `TelephonyCallback.CallStateListener` on API 31+ (needs `READ_PHONE_STATE`), the
 *   `ACTION_PHONE_STATE_CHANGED` broadcast's `EXTRA_STATE` below that. Reported on change only.
 * - **Caller**: the same broadcast's `EXTRA_INCOMING_NUMBER`, which Android only fills for an app
 *   that also holds `READ_CALL_LOG` (it then sends the broadcast twice, with and without it). The
 *   number is looked up in `ContactsContract.PhoneLookup` (`READ_CONTACTS`); without that
 *   permission or a match the caller is the number alone.
 * - **Answer / decline / hang up**: `TelecomManager.acceptRingingCall()` / `endCall()`
 *   (`ANSWER_PHONE_CALLS`). Both are deprecated for wearables' sake since API 29 but are still the
 *   only way for an app that is not the dialer; a refusal is logged and the phone's own UI remains.
 *
 * Everything runs on its own `motoparty-call` thread (registration, the contact query and the
 * telecom calls are binder calls), in the style of [AudioModeWatch]; [onState] and [onCaller] are
 * called on it and must hop to Main themselves. Missing permissions degrade, never throw: no
 * `READ_PHONE_STATE` = no call handling at all, which [ensureStarted] retries once it is granted.
 */
class CallWatch(
    context: Context,
    private val onState: (CallController.State) -> Unit,
    private val onCaller: (CallController.Caller) -> Unit,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val context = context.applicationContext
    private val thread = HandlerThread("motoparty-call").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)

    @Volatile private var started = false
    private var listener: Any? = null
    private var receiver: BroadcastReceiver? = null
    /** Last state reported (watcher thread only). */
    private var last = CallController.State.IDLE
    /** The number already looked up for this ring, so the second broadcast is not a second query. */
    private var lookedUp: String? = null
    /** A caller whose broadcast beat the callback's RINGING: reported right after it. */
    private var pending: CallController.Caller? = null

    /** May the Ride screen show Answer / Decline / End? */
    val canAnswer: Boolean get() = granted(Manifest.permission.ANSWER_PHONE_CALLS)

    /**
     * Register, if not yet done and `READ_PHONE_STATE` is granted. Cheap when already started:
     * the host calls it every second, so a permission granted mid-ride takes effect.
     */
    fun ensureStarted() {
        if (started || !granted(Manifest.permission.READ_PHONE_STATE)) return
        started = true
        handler.post(::register)
    }

    fun stop() {
        handler.post {
            if (Build.VERSION.SDK_INT >= 31) {
                (listener as? TelephonyCallback)?.let { runCatching { telephony.unregisterTelephonyCallback(it) } }
            }
            listener = null
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
            thread.quitSafely()
        }
    }

    fun accept() {
        handler.post {
            if (!canAnswer) return@post log("call: answer refused, no ANSWER_PHONE_CALLS")
            @Suppress("DEPRECATION")
            runCatching { telecom.acceptRingingCall() }.onFailure { log("call: acceptRingingCall failed: $it") }
        }
    }

    fun decline() = end("decline")

    fun hangUp() = end("hang up")

    private fun end(what: String) {
        handler.post {
            if (!canAnswer) return@post log("call: $what refused, no ANSWER_PHONE_CALLS")
            @Suppress("DEPRECATION")
            runCatching { telecom.endCall() }
                .onSuccess { ok -> if (!ok) log("call: $what: endCall returned false") }
                .onFailure { log("call: endCall failed: $it") }
        }
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE is checked in ensureStarted.
    private fun register() {
        if (Build.VERSION.SDK_INT >= 31) {
            val l = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = report(state)
            }
            runCatching { telephony.registerTelephonyCallback(executor, l) }
                .onSuccess { listener = l }
                .onFailure { log("call watch: callback refused: $it") }
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onBroadcast(intent)
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        // A protected system broadcast: delivered to a not-exported receiver.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(r, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(r, filter, null, handler)
            }
        }.onSuccess { receiver = r }.onFailure { log("call watch: broadcast refused: $it") }
        log(
            "call watch: on (state ${if (listener != null) "callback" else "broadcast"}, " +
                "number ${if (granted(Manifest.permission.READ_CALL_LOG)) "yes" else "no (call log)"}, " +
                "names ${if (granted(Manifest.permission.READ_CONTACTS)) "yes" else "no (contacts)"}, " +
                "answer ${if (canAnswer) "yes" else "no"})",
        )
    }

    private fun onBroadcast(intent: Intent) {
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }
        // Below API 31 (or with the callback refused) the broadcast is the state source too; on
        // 31+ the callback is, and a late broadcast must not re-report a state already left.
        if (listener == null) report(state)
        if (state != TelephonyManager.CALL_STATE_RINGING) return
        // The key is only there for an app with READ_CALL_LOG; its value is "" for a withheld number.
        if (!intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        if (number == lookedUp) return
        lookedUp = number
        val caller = CallController.Caller(name = if (number.isEmpty()) null else contactName(number), number = number)
        if (last == CallController.State.RINGING) onCaller(caller) else pending = caller
    }

    private fun report(state: Int) {
        val s = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallController.State.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallController.State.OFFHOOK
            else -> CallController.State.IDLE
        }
        if (s == last) return
        last = s
        if (s == CallController.State.IDLE) lookedUp = null
        onState(s)
        val p = pending
        pending = null
        if (s == CallController.State.RINGING && p != null) onCaller(p)
    }

    /** The contact's display name for [number], or null (no permission, no match, a failure). */
    private fun contactName(number: String): String? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.onFailure { log("call: contact lookup failed: $it") }.getOrNull()
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CallWatch"
    }
}
