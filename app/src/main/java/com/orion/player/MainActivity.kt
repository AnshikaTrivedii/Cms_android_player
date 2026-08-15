package com.orion.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
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
import com.orion.player.data.recovery.AutoStartLogger
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
        if (launchSource.startsWith("boot.")) {
            AutoStartLogger.bootRecovery(launchSource)
        }
        applyDedicatedHomeIfNeeded()

        if (securePrefs.isPaired && securePrefs.deviceToken.isNullOrBlank()) {
            securePrefs.clearCredentials()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        keepScreenAwakeForSignage()
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
        AutoStartCoordinator.onPlayerVisible(this, launchSource)
        AutoStartLogger.homeAppStatus(AutoStartCoordinator.isDefaultHomeApp(this))
        requestHomeRoleOncePerBoot()
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

    /**
     * A signage panel must show content even when the device boots to a lock screen.
     * FLAG_KEEP_SCREEN_ON alone does not wake the display or draw over the keyguard, and
     * these window flags are scoped to this Activity rather than disabling power
     * management for the whole device.
     */
    private fun keepScreenAwakeForSignage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        if (securePrefs.kioskModeEnabled) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    /** Device owner always pins Home. The stored flag covers a later opt-in. */
    private fun applyDedicatedHomeIfNeeded() {
        if (KioskController.isDeviceOwner(this)) {
            securePrefs.homeAppModeEnabled = true
            KioskController.applyDedicatedDevicePolicies(this)
            return
        }
        if (securePrefs.homeAppModeEnabled) {
            KioskController.applyHomeAppRole(this, enabled = true)
        }
    }

    /**
     * Ask once per boot to become the default Home app. Device-owner devices skip
     * the picker because persistent Home is applied in [applyDedicatedHomeIfNeeded].
     */
    private fun requestHomeRoleOncePerBoot() {
        if (AutoStartCoordinator.isDefaultHomeApp(this) || KioskController.isDeviceOwner(this)) {
            return
        }
        val store = BootStateStore.from(this)
        if (!store.shouldPromptHomeRole()) return
        store.markHomeRolePrompted()
        KioskController.requestHomeRole(this)
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
