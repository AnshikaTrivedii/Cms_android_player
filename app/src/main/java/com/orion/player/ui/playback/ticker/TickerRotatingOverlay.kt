package com.orion.player.ui.playback.ticker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerPosition
import com.orion.player.data.ticker.TickerPriority
import com.orion.player.data.ticker.rotationQueue
import kotlinx.coroutines.delay

private const val TICKER_ROTATION_INTERVAL_MS = 15_000L
private const val URGENT_ROTATION_INTERVAL_MS = 20_000L

/**
 * Layer 2 overlay: rotates tickers per position without crossfade flicker.
 * URGENT tickers override the rotation queue at each position.
 */
@Composable
fun TickerRotatingOverlay(
    tickers: List<TickerDisplayConfig>,
    modifier: Modifier = Modifier
) {
    if (tickers.isEmpty()) return

    val topTickers = remember(tickers) {
        tickers.filter { it.position == TickerPosition.TOP }.rotationQueue()
    }
    val bottomTickers = remember(tickers) {
        tickers.filter { it.position == TickerPosition.BOTTOM }.rotationQueue()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (topTickers.isNotEmpty()) {
            PositionTickerRotator(
                tickers = topTickers,
                alignment = Alignment.TopCenter
            )
        }
        if (bottomTickers.isNotEmpty()) {
            PositionTickerRotator(
                tickers = bottomTickers,
                alignment = Alignment.BottomCenter
            )
        }
    }
}

@Composable
private fun BoxScope.PositionTickerRotator(
    tickers: List<TickerDisplayConfig>,
    alignment: Alignment
) {
    var currentIndex by remember(tickers) { mutableIntStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, (tickers.size - 1).coerceAtLeast(0))
    val currentTicker = tickers[safeIndex]
    val rotationInterval = if (tickers.any { it.priority == TickerPriority.URGENT }) {
        URGENT_ROTATION_INTERVAL_MS
    } else {
        TICKER_ROTATION_INTERVAL_MS
    }

    LaunchedEffect(tickers) {
        currentIndex = 0
    }

    LaunchedEffect(tickers, safeIndex, rotationInterval) {
        if (tickers.size <= 1) return@LaunchedEffect
        delay(rotationInterval)
        currentIndex = (safeIndex + 1) % tickers.size
    }

    key(currentTicker.id) {
        TickerOverlay(
            config = currentTicker,
            modifier = Modifier.align(alignment)
        )
    }
}
