package com.orion.player.ui.playback.ticker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.orion.player.data.ticker.TickerDisplayConfig

/**
 * Professional digital signage layout:
 * - Layer 1 (zIndex 0): full-screen content playback
 * - Layer 2 (zIndex 1): ticker overlay — never replaces or interrupts content
 */
@Composable
fun SignageLayeredPlayback(
    tickers: List<TickerDisplayConfig>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
        ) {
            content()
        }

        if (tickers.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
            ) {
                TickerRotatingOverlay(tickers = tickers)
            }
        }
    }
}
