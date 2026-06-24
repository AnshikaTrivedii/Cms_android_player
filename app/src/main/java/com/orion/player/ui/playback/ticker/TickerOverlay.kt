package com.orion.player.ui.playback.ticker

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerHeight
import com.orion.player.data.ticker.TickerLogger
import com.orion.player.data.ticker.TickerPosition
import com.orion.player.data.ticker.TickerSpeed

@Composable
fun TickerOverlay(
    config: TickerDisplayConfig,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(config.id, config.text) {
        TickerLogger.rendered(config)
    }

    val barHeight = config.height.toBarHeight()
    val fontSize = config.height.toFontSize()
    val backgroundColor = parseTickerColor(config.backgroundColorHex, Color.Black)
    val textColor = parseTickerColor(config.textColorHex, Color.White)

    val emergencyHighlight = if (config.isEmergency) {
        val transition = rememberInfiniteTransition(label = "urgentPulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "urgentHighlightAlpha"
        ).value
    } else {
        0f
    }

    val emergencyBorderColor = Color(0xFFFFD600).copy(alpha = emergencyHighlight.coerceIn(0f, 1f))
    val emergencyBackgroundBoost = Color.White.copy(alpha = emergencyHighlight * 0.12f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .then(
                if (config.isEmergency) {
                    Modifier.border(width = 2.dp, color = emergencyBorderColor)
                } else {
                    Modifier
                }
            )
            .background(backgroundColor)
            .background(emergencyBackgroundBoost)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = config.text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = if (config.isEmergency) FontWeight.Bold else FontWeight.SemiBold,
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

private fun TickerHeight.toBarHeight(): Dp = when (this) {
    TickerHeight.SMALL -> 36.dp
    TickerHeight.MEDIUM -> 48.dp
    TickerHeight.LARGE -> 64.dp
}

private fun TickerHeight.toFontSize(): TextUnit = when (this) {
    TickerHeight.SMALL -> 14.sp
    TickerHeight.MEDIUM -> 18.sp
    TickerHeight.LARGE -> 24.sp
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
