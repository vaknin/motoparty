package com.kivan.motoparty

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kivan.motoparty.ui.MainScreen
import com.kivan.motoparty.ui.MotopartyTheme
import com.kivan.motoparty.ui.Permission
import com.kivan.motoparty.ui.deniedForGood

class MainActivity : ComponentActivity() {
    /** Bumped whenever permissions may have changed, so the screen re-reads them. */
    private var permissionEpoch by mutableIntStateOf(0)

    /** The permission "Allow" was last pressed for, and when: see [deniedForGood]. */
    private var asked: String? = null
    private var askedAtMs = 0L

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionEpoch++
            // After a permanent denial the system answers "denied" at once without asking, and
            // "Allow" would do nothing (UA14): the app's page in Settings is the only way left.
            val key = asked ?: return@registerForActivityResult
            asked = null
            if (deniedForGood(granted(key), shouldShowRequestPermissionRationale(key), SystemClock.elapsedRealtime() - askedAtMs)) {
                openAppSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MotopartyTheme {
                @Suppress("UNUSED_EXPRESSION") permissionEpoch
                MainScreen(
                    permissions = permissions(),
                    onGrant = ::grant,
                    onStart = { LinkService.start(this) },
                    onStop = { LinkService.stop(this) },
                    onRestoreMic = ::restoreMic,
                )
            }
        }
        if (runtimePermissions.any { !granted(it) }) requestPermissions.launch(runtimePermissions.toTypedArray())
        // The overlay setting is no longer watched here: LinkService collects it, so the buttons
        // also appear and disappear when this activity is gone (F6).
        // Only on a fresh launch: a recreation (rotation, font scale, dark mode) after Stop must
        // not bring the host back. A host that is running is re-claimed in onResume.
        if (savedInstanceState == null) LinkService.start(this)
    }

    override fun onResume() {
        super.onResume()
        permissionEpoch++
        if (Hub.status.value.running) {
            // Re-claims the microphone service type if RECORD_AUDIO was granted meanwhile.
            LinkService.start(this)
            LinkService.maybeStartOverlay(this)
        }
    }

    private val runtimePermissions: List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 37) add(ACCESS_LOCAL_NETWORK)
        // Phone calls while riding (2026-10-02); see audio/CallWatch.kt for what each one adds.
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun permissions(): List<Permission> =
        runtimePermissions.map { Permission(label(it), granted(it), it) } +
            Permission("Draw over other apps", AndroidSettings.canDrawOverlays(this), OVERLAY)

    private fun label(p: String) = when (p) {
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        Manifest.permission.BLUETOOTH_CONNECT -> "Nearby devices (Bluetooth)"
        ACCESS_LOCAL_NETWORK -> "Local network"
        Manifest.permission.READ_PHONE_STATE -> "Phone (mute music for calls)"
        Manifest.permission.READ_CALL_LOG -> "Call log (caller's number)"
        Manifest.permission.READ_CONTACTS -> "Contacts (caller's name)"
        Manifest.permission.ANSWER_PHONE_CALLS -> "Answer calls from the Ride screen"
        else -> p.substringAfterLast('.')
    }

    private fun grant(p: Permission) {
        if (p.key == OVERLAY) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else if (!granted(p.key)) {
            asked = p.key
            askedAtMs = SystemClock.elapsedRealtime()
            requestPermissions.launch(arrayOf(p.key))
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /**
     * The Ride tab's "Microphone off" warning ([LinkStatus.micOff]): without the permission, ask
     * for it (the service claims the microphone in onResume, once it is granted); with it, the
     * service only has to claim its `microphone` type again, which it can now that we are in front.
     */
    private fun restoreMic() {
        val mic = Manifest.permission.RECORD_AUDIO
        if (granted(mic)) LinkService.start(this) else grant(Permission(label(mic), false, mic))
    }

    companion object {
        private const val OVERLAY = "overlay"
        private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
