package com.orion.player.ui.playback.ticker

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerPosition
import com.orion.player.data.ticker.TickerSpeed

@Composable
fun TickerOverlay(
    config: TickerDisplayConfig,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TICKER_HEIGHT)
            .background(parseTickerColor(config.backgroundColorHex, Color.Black))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = config.text,
            color = parseTickerColor(config.textColorHex, Color.White),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    velocity = config.speed.toVelocity()
                )
        )
    }
}

fun TickerPosition.toAlignment(): Alignment = when (this) {
    TickerPosition.TOP -> Alignment.TopCenter
    TickerPosition.BOTTOM -> Alignment.BottomCenter
}

private fun TickerSpeed.toVelocity(): Dp = when (this) {
    TickerSpeed.SLOW -> 40.dp
    TickerSpeed.NORMAL -> 80.dp
    TickerSpeed.FAST -> 140.dp
}

private fun parseTickerColor(hex: String, fallback: Color): Color {
    return try {
        val cleaned = hex.trim().removePrefix("#")
        val argb = when (cleaned.length) {
            6 -> "FF$cleaned"
            8 -> cleaned
            else -> return fallback
        }
        Color(android.graphics.Color.parseColor("#$argb"))
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

private val TICKER_HEIGHT = 48.dp
