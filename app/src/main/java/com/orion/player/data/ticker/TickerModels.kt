package com.orion.player.data.ticker

/**
 * Ticker configuration from GET /player/sync.
 */
data class TickerInfo(
    val id: String,
    val text: String,
    val position: String,
    val speed: String,
    val priority: String,
    val backgroundColor: String,
    val textColor: String,
    val isActive: Boolean = true
)

enum class TickerPosition {
    TOP,
    BOTTOM;

    companion object {
        fun from(raw: String): TickerPosition = when (raw.uppercase()) {
            "TOP" -> TOP
            else -> BOTTOM
        }
    }
}

enum class TickerSpeed {
    SLOW,
    NORMAL,
    FAST;

    companion object {
        fun from(raw: String): TickerSpeed = when (raw.uppercase()) {
            "SLOW" -> SLOW
            "FAST" -> FAST
            else -> NORMAL
        }
    }
}

enum class TickerPriority {
    URGENT,
    NORMAL,
    LOW;

    val rank: Int
        get() = when (this) {
            URGENT -> 3
            NORMAL -> 2
            LOW -> 1
        }

    companion object {
        fun from(raw: String): TickerPriority = when (raw.uppercase()) {
            "URGENT" -> URGENT
            "LOW" -> LOW
            else -> NORMAL
        }
    }
}

data class TickerDisplayConfig(
    val id: String,
    val text: String,
    val position: TickerPosition,
    val speed: TickerSpeed,
    val backgroundColorHex: String,
    val textColorHex: String
)

fun List<TickerInfo>.resolveActiveTicker(): TickerDisplayConfig? =
    asSequence()
        .filter { it.isActive && it.text.isNotBlank() }
        .maxByOrNull { TickerPriority.from(it.priority).rank }
        ?.toDisplayConfig()

fun TickerInfo.toDisplayConfig(): TickerDisplayConfig = TickerDisplayConfig(
    id = id,
    text = text.trim(),
    position = TickerPosition.from(position),
    speed = TickerSpeed.from(speed),
    backgroundColorHex = backgroundColor,
    textColorHex = textColor
)
