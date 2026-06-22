package com.orion.player

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
import com.orion.player.ui.navigation.OrionNavGraph
import com.orion.player.ui.navigation.Routes
import com.orion.player.ui.theme.OrionPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single Activity host for the Orion Player.
 * Configures immersive fullscreen, keeps screen on, and hosts the Compose NavGraph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var securePrefs: SecurePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Clear stale paired flag left from older builds without a valid token
        if (securePrefs.isPaired && securePrefs.deviceToken.isNullOrBlank()) {
            securePrefs.clearCredentials()
        }

        // Keep screen on at all times (digital signage requirement)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Edge-to-edge immersive mode
        enableEdgeToEdge()
        setupImmersiveMode()

        val startDestination = if (
            securePrefs.isPaired && !securePrefs.deviceToken.isNullOrBlank()
        ) {
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

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
    }

    /**
     * Hides system bars (status bar + navigation bar) for true kiosk experience.
     */
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
