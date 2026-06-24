package com.orion.player.data.ticker

/**
 * Ticker configuration from GET /player/sync.
 * Device targeting is resolved by the backend — the player renders all tickers returned.
 */
data class TickerInfo(
    val id: String,
    val text: String,
    val scope: String = "ALL_DEVICES",
    val position: String = "BOTTOM",
    val height: String = "MEDIUM",
    val speed: String = "NORMAL",
    val priority: String = "NORMAL",
    val backgroundColor: String = "#000000",
    val textColor: String = "#FFFFFF",
    val isActive: Boolean = true
)

enum class TickerScope {
    ALL_DEVICES,
    SELECTED_DEVICES;

    companion object {
        fun from(raw: String): TickerScope = when (raw.uppercase()) {
            "SELECTED_DEVICES" -> SELECTED_DEVICES
            else -> ALL_DEVICES
        }
    }
}

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

enum class TickerHeight {
    SMALL,
    MEDIUM,
    LARGE;

    companion object {
        fun from(raw: String): TickerHeight = when (raw.uppercase()) {
            "SMALL" -> SMALL
            "LARGE" -> LARGE
            else -> MEDIUM
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
    val scope: TickerScope,
    val position: TickerPosition,
    val height: TickerHeight,
    val speed: TickerSpeed,
    val priority: TickerPriority,
    val backgroundColorHex: String,
    val textColorHex: String
) {
    val isEmergency: Boolean
        get() = priority == TickerPriority.URGENT
}

/**
 * Returns all active tickers from sync, sorted by priority (URGENT → NORMAL → LOW).
 * No local device filtering — backend returns only tickers for this device.
 */
fun List<TickerInfo>.resolveActiveTickers(): List<TickerDisplayConfig> =
    asSequence()
        .filter { it.isActive && it.text.isNotBlank() }
        .sortedByDescending { TickerPriority.from(it.priority).rank }
        .map { it.toDisplayConfig() }
        .toList()

/**
 * Emergency override: when URGENT tickers exist at a position, only they rotate.
 */
fun List<TickerDisplayConfig>.rotationQueue(): List<TickerDisplayConfig> {
    val urgent = filter { it.priority == TickerPriority.URGENT }
    return if (urgent.isNotEmpty()) urgent else this
}

fun TickerInfo.toDisplayConfig(): TickerDisplayConfig = TickerDisplayConfig(
    id = id,
    text = text.trim(),
    scope = TickerScope.from(scope),
    position = TickerPosition.from(position),
    height = TickerHeight.from(height),
    speed = TickerSpeed.from(speed),
    priority = TickerPriority.from(priority),
    backgroundColorHex = backgroundColor.ifBlank { "#000000" },
    textColorHex = textColor.ifBlank { "#FFFFFF" }
)
