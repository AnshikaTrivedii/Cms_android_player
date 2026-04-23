package com.orion.player.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Dark color scheme for the Orion Player.
 * Digital signage displays almost always run in dark mode.
 */
private val OrionDarkColorScheme = darkColorScheme(
    primary = OrionPurple,
    onPrimary = TextPrimary,
    primaryContainer = OrionPurpleDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentCyan,
    onSecondary = DarkBackground,
    secondaryContainer = AccentTeal,
    onSecondaryContainer = TextPrimary,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = StatusOffline,
    onError = TextPrimary,
    outline = TextMuted
)

@Composable
fun OrionPlayerTheme(content: @Composable () -> Unit) {
    val colorScheme = OrionDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OrionTypography,
        content = content
    )
}
