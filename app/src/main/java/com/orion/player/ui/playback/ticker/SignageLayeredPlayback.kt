package com.orion.player.ui.playback.ticker

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerHeightPercent
import com.orion.player.data.ticker.TickerPosition

/**
 * Full-screen playlist mode: splits screen between content and highest-priority ticker.
 */
@Composable
fun SignageLayeredPlayback(
    tickers: List<TickerDisplayConfig>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val ticker = tickers.firstOrNull()

    if (ticker == null) {
        Box(modifier = modifier.fillMaxSize()) { content() }
        return
    }

    val tickerWeight = TickerHeightPercent.clamp(ticker.heightPercent).toFloat()
    val contentWeight = (100f - tickerWeight).coerceAtLeast(1f)

    Column(modifier = modifier.fillMaxSize()) {
        when (ticker.position) {
            TickerPosition.TOP -> {
                TickerBarSlot(ticker, tickerWeight)
                ContentSlot(contentWeight, content)
            }
            TickerPosition.BOTTOM -> {
                ContentSlot(contentWeight, content)
                TickerBarSlot(ticker, tickerWeight)
            }
        }
    }
}

@Composable
private fun ColumnScope.ContentSlot(
    weight: Float,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(weight)
    ) {
        content()
    }
}

@Composable
private fun ColumnScope.TickerBarSlot(
    ticker: TickerDisplayConfig,
    weight: Float
) {
    TickerRenderer(
        config = ticker,
        modifier = Modifier
            .fillMaxWidth()
            .weight(weight)
    )
}
