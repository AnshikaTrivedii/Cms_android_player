package com.orion.player.ui.playback

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.orion.player.ui.theme.StatusOffline
import java.time.Instant

/**
 * Tiny offline icon in a WindowManager [Popup] so it stays above video/WebView.
 * Detection logic lives in [com.orion.player.util.NetworkMonitor] and is unchanged.
 */
@Composable
fun OfflineStatusBadge(visible: Boolean) {
    LaunchedEffect(visible) {
        Log.i(
            TAG,
            "OFFLINE_DEBUG: uiOfflineState=$visible " +
                "indicatorVisibility=${if (visible) "VISIBLE" else "GONE"} " +
                "layer=WINDOW_POPUP timestamp=${Instant.now()}"
        )
    }

    if (!visible) return

    Popup(
        alignment = Alignment.TopEnd,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 12.dp, end = 12.dp)
                .size(28.dp)
                .background(StatusOffline.copy(alpha = 0.92f), CircleShape)
                .semantics { contentDescription = "Offline" }
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private const val TAG = "OrionNetwork"
