package com.orion.player.ui.playback

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When true, media players use fill/crop scaling to cover the entire display.
 */
val LocalStretchToFit = staticCompositionLocalOf { false }
