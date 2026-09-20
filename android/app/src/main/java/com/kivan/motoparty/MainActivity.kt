package com.kivan.motoparty

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kivan.motoparty.ui.MainScreen
import com.kivan.motoparty.ui.Permission

class MainActivity : ComponentActivity() {
    /** Bumped whenever permissions may have changed, so the screen re-reads them. */
    private var permissionEpoch by mutableIntStateOf(0)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionEpoch++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                @Suppress("UNUSED_EXPRESSION") permissionEpoch
                MainScreen(
                    permissions = permissions(),
                    onGrant = ::grant,
                    onStart = { LinkService.start(this) },
                    onStop = { LinkService.stop(this) },
                )
            }
        }
        if (runtimePermissions.any { !granted(it) }) requestPermissions.launch(runtimePermissions.toTypedArray())
        // The overlay setting is no longer watched here: LinkService collects it, so the buttons
        // also appear and disappear when this activity is gone (F6).
        LinkService.start(this)
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
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun permissions(): List<Permission> =
        runtimePermissions.map { Permission(it.substringAfterLast('.'), granted(it), it) } +
            Permission("Draw over other apps", AndroidSettings.canDrawOverlays(this), OVERLAY)

    private fun grant(p: Permission) {
        if (p.key == OVERLAY) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else if (shouldShowRequestPermissionRationale(p.key) || !granted(p.key)) {
            requestPermissions.launch(arrayOf(p.key))
        }
    }

    companion object {
        private const val OVERLAY = "overlay"
        private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
