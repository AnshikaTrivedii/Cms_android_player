package com.orion.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.orion.player.data.local.SecurePrefs
import com.orion.player.ui.pairing.PairingScreen
import com.orion.player.ui.playback.PlaybackScreen

/**
 * Navigation routes for the Orion Player.
 */
object Routes {
    const val PAIRING = "pairing"
    const val PLAYBACK = "playback"
}

/**
 * Top-level NavGraph.
 * Start destination depends on whether the device is already paired.
 */
@Composable
fun OrionNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.PAIRING
    ) {
        composable(Routes.PAIRING) {
            PairingScreen(
                onPaired = {
                    navController.navigate(Routes.PLAYBACK) {
                        popUpTo(Routes.PAIRING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PLAYBACK) {
            PlaybackScreen(
                onUnpaired = {
                    navController.navigate(Routes.PAIRING) {
                        popUpTo(Routes.PLAYBACK) { inclusive = true }
                    }
                }
            )
        }
    }
}
