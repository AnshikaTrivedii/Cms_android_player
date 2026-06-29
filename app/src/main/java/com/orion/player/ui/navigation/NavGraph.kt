package com.orion.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.orion.player.ui.cache.CacheDebugScreen
import com.orion.player.ui.pairing.PairingScreen
import com.orion.player.ui.playback.PlaybackScreen

/**
 * Navigation routes for the Orion Player.
 */
object Routes {
    const val PAIRING = "pairing"
    const val PLAYBACK = "playback"
    const val CACHE_DEBUG = "cache_debug"
}

/**
 * Top-level NavGraph.
 * Start destination depends on whether the device is already paired.
 */
@Composable
fun OrionNavGraph(
    startDestination: String = Routes.PAIRING
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
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
                },
                onOpenCacheDebug = {
                    navController.navigate(Routes.CACHE_DEBUG)
                }
            )
        }

        composable(Routes.CACHE_DEBUG) {
            CacheDebugScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
