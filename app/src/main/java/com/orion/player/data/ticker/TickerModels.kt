package com.orion.player.data.ticker

/**
 * Ticker configuration from GET /player/sync.
 * Device targeting is resolved by the backend — the player renders the highest-priority active ticker.
 *
 * `heightPercent` is the percentage of screen height (10–20) the ticker bar occupies; the
 * remaining (100 − heightPercent)% is reserved for playlist content so they never overlap.
 */
/**
 * Fields are nullable because the JSON is parsed by Gson, which instantiates the class without
 * running the Kotlin constructor — so missing fields fall back to JVM defaults (null / false /
 * 0), NOT to the Kotlin default values. Keeping them nullable lets [toDisplayConfig] apply real
 * defaults defensively. In particular the backend omits `isActive`/`scope` (it only returns active
 * tickers for this device), so we must treat a missing `isActive` as active.
 */
data class TickerInfo(
    val id: String? = null,
    val text: String? = null,
    val scope: String? = null,
    val position: String? = null,
    val speed: String? = null,
    val heightPercent: Int? = null,
    /** Legacy CMS field (SMALL/MEDIUM/LARGE) — used when [heightPercent] is absent. */
    val height: String? = null,
    val style: String? = null,
    val priority: String? = null,
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val isActive: Boolean? = null
)

object TickerHeightPercent {
    const val MIN = 10
    const val MAX = 20
    const val DEFAULT = 12

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

enum class TickerScope {
    ALL_DEVICES,
    SELECTED_DEVICES;

    companion object {
        fun from(raw: String?): TickerScope = when (raw?.uppercase()) {
            "SELECTED_DEVICES" -> SELECTED_DEVICES
            else -> ALL_DEVICES
        }
    }
}

enum class TickerPosition {
    TOP,
    BOTTOM;

    companion object {
        fun from(raw: String?): TickerPosition = when (raw?.uppercase()) {
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
        fun from(raw: String?): TickerSpeed = when (raw?.uppercase()) {
            "SLOW" -> SLOW
            "FAST" -> FAST
            else -> NORMAL
        }
    }
}

enum class TickerStyle {
    CLASSIC,
    NEON,
    GRADIENT,
    MINIMAL;

    companion object {
        fun from(raw: String?): TickerStyle = when (raw?.uppercase()) {
            "NEON" -> NEON
            "GRADIENT" -> GRADIENT
            "MINIMAL" -> MINIMAL
            else -> CLASSIC
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
        fun from(raw: String?): TickerPriority = when (raw?.uppercase()) {
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
    val speed: TickerSpeed,
    val heightPercent: Int,
    val style: TickerStyle,
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
        // Treat a missing isActive (null) as active — backend only sends active tickers.
        .filter { it.isActive != false && !it.text.isNullOrBlank() }
        .sortedByDescending { TickerPriority.from(it.priority).rank }
        .map { it.toDisplayConfig() }
        .toList()

fun TickerInfo.toDisplayConfig(): TickerDisplayConfig = TickerDisplayConfig(
    id = id.orEmpty(),
    text = text.orEmpty().trim(),
    scope = TickerScope.from(scope),
    position = TickerPosition.from(position),
    speed = TickerSpeed.from(speed),
    heightPercent = TickerHeightPercent.clamp(
        heightPercent ?: legacyHeightToPercent(height)
    ),
    style = TickerStyle.from(style),
    priority = TickerPriority.from(priority),
    backgroundColorHex = backgroundColor?.takeIf { it.isNotBlank() } ?: "#000000",
    textColorHex = textColor?.takeIf { it.isNotBlank() } ?: "#FFFFFF"
)

private fun legacyHeightToPercent(raw: String?): Int = when (raw?.uppercase()) {
    "SMALL" -> 10
    "LARGE" -> 18
    "MEDIUM" -> 14
    else -> TickerHeightPercent.DEFAULT
}
