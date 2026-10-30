package com.orion.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.orion.player.data.config.DeviceConfigManager
import com.orion.player.data.config.DisplayOrientation
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.AutoStartCoordinator
import com.orion.player.data.recovery.BootStateStore
import com.orion.player.data.recovery.KioskController
import com.orion.player.data.recovery.OrionRecoveryLogger
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.recovery.PlayerLaunchHelper
import com.orion.player.service.PlayerForegroundService
import com.orion.player.ui.navigation.OrionNavGraph
import com.orion.player.ui.navigation.Routes
import com.orion.player.ui.theme.OrionPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single Activity host for the Orion Player.
 * Configures immersive fullscreen, optional kiosk lock-task, and hosts the Compose NavGraph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var securePrefs: SecurePrefs
    @Inject lateinit var healthMonitor: PlayerHealthMonitor
    @Inject lateinit var deviceConfigManager: DeviceConfigManager

    private var launchSource: String = "activity.onCreate"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launchSource = intent?.getStringExtra(PlayerLaunchHelper.EXTRA_LAUNCH_SOURCE)
            ?: "activity.onCreate"
        OrionRecoveryLogger.logPlayerStarted(launchSource)
        // Older builds saved kiosk-on and registered this app as the Home app, which
        // trapped Back behind the Leave player screen. Drop both so Back can exit.
        securePrefs.kioskModeEnabled = false
        runCatching { packageManager.clearPackagePreferredActivities(packageName) }
        KioskController.clearLegacyHomeAssignment(this)
        if (securePrefs.isPaired && securePrefs.deviceToken.isNullOrBlank()) {
            securePrefs.clearCredentials()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setupImmersiveMode()
        applyDisplayOrientation(deviceConfigManager.orientation.value)
        PlayerForegroundService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deviceConfigManager.orientation.collect { orientation ->
                    applyDisplayOrientation(orientation)
                }
            }
        }

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
        // The only reliable proof that the player reached the screen: a background
        // activity start that the platform blocks fails silently.
        AutoStartCoordinator.onPlayerVisible(this)
    }

    override fun onStop() {
        healthMonitor.recordActivityPaused()
        AutoStartCoordinator.onPlayerHidden()
        super.onStop()
    }

    override fun onDestroy() {
        // Only a deliberate finish counts as a clean stop; anything else leaves the
        // running marker set so the next process start is reported as a recovery.
        if (isFinishing) {
            BootStateStore.from(this).markPlayerStopped()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        applyKioskModeIfEnabled()
        applyDisplayOrientation(deviceConfigManager.orientation.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(PlayerLaunchHelper.EXTRA_LAUNCH_SOURCE)?.let { source ->
            launchSource = source
            OrionRecoveryLogger.logPlayerStarted(source)
        }
    }

    private fun applyDisplayOrientation(orientation: DisplayOrientation) {
        val target = when (orientation) {
            DisplayOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            DisplayOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != target) {
            OrionRecoveryLogger.logPlayerStarted("orientation.${orientation.name.lowercase()}")
            requestedOrientation = target
        }
    }

    private fun applyKioskModeIfEnabled() {
        val kioskRequested = securePrefs.kioskModeEnabled
        if (kioskRequested) {
            OrionRecoveryLogger.logKioskModeEnabled(true)
            KioskController.ensureLockTaskAllowed(this)
        }
        KioskController.applyIfPermitted(this, kioskRequested)
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
