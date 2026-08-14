package com.orion.player.ui.playback

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CMS device-level Stretch to Fit flag.
 *
 * Images: ContentScale.FillBounds when on (fill container, keep all pixels).
 * Videos: AspectRatioFrameLayout.RESIZE_MODE_FILL (true stretch; may distort).
 */
val LocalStretchToFit = staticCompositionLocalOf { false }
