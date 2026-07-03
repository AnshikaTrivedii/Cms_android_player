package com.orion.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.OrionRecoveryLogger
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.recovery.PlayerLaunchHelper
import com.orion.player.service.PlayerForegroundService
import com.orion.player.ui.navigation.OrionNavGraph
import com.orion.player.ui.navigation.Routes
import com.orion.player.ui.theme.OrionPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single Activity host for the Orion Player.
 * Configures immersive fullscreen, optional kiosk lock-task, and hosts the Compose NavGraph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var securePrefs: SecurePrefs
    @Inject lateinit var healthMonitor: PlayerHealthMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launchSource = intent?.getStringExtra(PlayerLaunchHelper.EXTRA_LAUNCH_SOURCE)
            ?: "activity.onCreate"
        OrionRecoveryLogger.logPlayerStarted(launchSource)

        if (securePrefs.isPaired && securePrefs.deviceToken.isNullOrBlank()) {
            securePrefs.clearCredentials()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setupImmersiveMode()
        PlayerForegroundService.start(this)

        val startDestination = if (securePrefs.isAuthenticated()) {
            Routes.PLAYBACK
        } else {
            Routes.PAIRING
        }

        setContent {
            OrionPlayerTheme {
                OrionNavGraph(startDestination = startDestination)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        healthMonitor.recordActivityResumed()
    }

    override fun onStop() {
        healthMonitor.recordActivityPaused()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        applyKioskModeIfEnabled()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (securePrefs.kioskModeEnabled && securePrefs.isAuthenticated()) {
            bringPlayerToForeground("kiosk.home")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(PlayerLaunchHelper.EXTRA_LAUNCH_SOURCE)?.let { source ->
            OrionRecoveryLogger.logPlayerStarted(source)
        }
    }

    private fun applyKioskModeIfEnabled() {
        if (!securePrefs.kioskModeEnabled) return
        OrionRecoveryLogger.logKioskModeEnabled(true)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                startLockTask()
            }
        } catch (_: IllegalStateException) {
            // Lock task requires device owner or screen pinning approval on some devices.
        } catch (_: SecurityException) {
            // HOME launcher category still keeps the player as the default shell.
        }
    }

    private fun bringPlayerToForeground(source: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PlayerLaunchHelper.EXTRA_LAUNCH_SOURCE, source)
        }
        startActivity(intent)
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }
}
