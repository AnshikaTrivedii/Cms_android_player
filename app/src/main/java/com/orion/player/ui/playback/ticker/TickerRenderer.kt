package com.orion.player.ui.playback.ticker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerLogger
import com.orion.player.data.ticker.TickerSpeed
import com.orion.player.data.ticker.TickerStyle

/** Gap between repeated ticker text copies for seamless looping (px). */
private const val TICKER_REPEAT_GAP_PX = 56f

/**
 * Seamless right→left marquee at CMS speeds (45 / 85 / 150 px/sec).
 * Uses [Animatable] instead of infinite transition for reliable animation on Android TV.
 */
@Composable
fun TickerRenderer(
    config: TickerDisplayConfig,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(config.id, config.text) {
        TickerLogger.rendered(config)
    }

    val backgroundColor = parseTickerColor(config.backgroundColorHex, Color.Black)
    val textColor = parseTickerColor(config.textColorHex, Color.White)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .styleBackground(config.style, backgroundColor)
            .styleBorder(config.style, textColor, config.isEmergency)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }

        val fontSize = (maxHeight.value * 0.42f).coerceIn(14f, 56f).sp
        val fontWeight = when {
            config.isEmergency -> FontWeight.Bold
            config.style == TickerStyle.NEON -> FontWeight.Bold
            config.style == TickerStyle.MINIMAL -> FontWeight.Medium
            else -> FontWeight.SemiBold
        }

        val textStyle = remember(fontSize, fontWeight, textColor) {
            TextStyle(fontSize = fontSize, fontWeight = fontWeight, color = textColor)
        }

        val textMeasurer = rememberTextMeasurer()
        val textWidthPx = remember(config.text, textStyle) {
            textMeasurer.measure(
                text = config.text,
                style = textStyle,
                softWrap = false,
                maxLines = 1
            ).size.width.toFloat().coerceAtLeast(1f)
        }

        if (containerWidthPx <= 1f) {
            TickerTextLine(config.text, textStyle)
            return@BoxWithConstraints
        }

        val cycleLengthPx = textWidthPx + TICKER_REPEAT_GAP_PX
        val travelPx = containerWidthPx + cycleLengthPx
        val velocityPxPerSec = config.speed.velocityPxPerSec()
        val durationMillis = ((travelPx / velocityPxPerSec) * 1000f).toInt().coerceAtLeast(1)
        val gapDp = with(density) { TICKER_REPEAT_GAP_PX.toDp() }

        val offsetX = remember(containerWidthPx, cycleLengthPx) {
            Animatable(containerWidthPx)
        }

        LaunchedEffect(containerWidthPx, cycleLengthPx, durationMillis) {
            while (true) {
                offsetX.snapTo(containerWidthPx)
                offsetX.animateTo(
                    targetValue = -cycleLengthPx,
                    animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing)
                )
            }
        }

        Row(
            modifier = Modifier.graphicsLayer { translationX = offsetX.value },
            verticalAlignment = Alignment.CenterVertically
        ) {
            TickerTextLine(config.text, textStyle)
            Spacer(modifier = Modifier.width(gapDp))
            TickerTextLine(config.text, textStyle)
            Spacer(modifier = Modifier.width(gapDp))
            TickerTextLine(config.text, textStyle)
        }
    }
}

@Composable
private fun TickerTextLine(text: String, style: TextStyle) {
    Text(
        text = text,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible
    )
}

/** CMS-matched scroll speeds in px/sec. */
fun TickerSpeed.velocityPxPerSec(): Float = when (this) {
    TickerSpeed.SLOW -> 45f
    TickerSpeed.NORMAL -> 85f
    TickerSpeed.FAST -> 150f
}

private fun Modifier.styleBackground(style: TickerStyle, base: Color): Modifier = when (style) {
    TickerStyle.GRADIENT -> background(Brush.horizontalGradient(listOf(base, base.darken(0.35f))))
    TickerStyle.MINIMAL -> background(base.copy(alpha = 0.55f))
    TickerStyle.NEON, TickerStyle.CLASSIC -> background(base)
}

private fun Modifier.styleBorder(style: TickerStyle, accent: Color, isEmergency: Boolean): Modifier =
    when {
        isEmergency -> border(width = 2.dp, color = Color(0xFFFFD600))
        style == TickerStyle.NEON -> border(width = 2.dp, color = accent.copy(alpha = 0.85f))
        else -> this
    }

private fun Color.darken(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(red = red * (1f - f), green = green * (1f - f), blue = blue * (1f - f), alpha = alpha)
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

/** @deprecated Use [TickerRenderer] */
@Composable
fun TickerOverlay(config: TickerDisplayConfig, modifier: Modifier = Modifier) {
    TickerRenderer(config = config, modifier = modifier)
}
