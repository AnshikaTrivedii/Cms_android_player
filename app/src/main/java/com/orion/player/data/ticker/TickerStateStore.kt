package com.orion.player.data.ticker

import android.util.Log
import com.orion.player.data.remote.LayoutInfo
import com.orion.player.data.remote.ZoneType
import com.orion.player.data.sync.PlayerPlaybackConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live overlay ticker from GET /player/sync. Targeting is server-side: the player
 * always replaces local state from `tickers[]` and never infers audience.
 *
 * Layouts with a TICKER zone keep the root overlay empty when layout playback is on
 * so zone.ticker renders inside the zone rect. If layout playback is off, the zone
 * ticker is promoted to the overlay so assign/unassign still applies in place.
 */
@Singleton
class TickerStateStore @Inject constructor() {
    private val _tickers = MutableStateFlow<List<TickerDisplayConfig>>(emptyList())
    val tickers: StateFlow<List<TickerDisplayConfig>> = _tickers.asStateFlow()

    fun replaceFromSync(raw: List<TickerInfo>, layout: LayoutInfo?) {
        val resolvedLayout = layout
        if (resolvedLayout.hasTickerZone() && resolvedLayout != null) {
            if (PlayerPlaybackConfig.LAYOUT_PLAYBACK_ENABLED) {
                publish(emptyList())
                return
            }
            val zoneTicker = resolvedLayout.zones
                .asSequence()
                .filter { it.type == ZoneType.TICKER }
                .mapNotNull { zone ->
                    zone.ticker
                        ?.takeIf { it.isActive != false && !it.text.isNullOrBlank() }
                        ?.toDisplayConfig()
                }
                .firstOrNull()
            publish(listOfNotNull(zoneTicker))
            return
        }
        // Server already sorts URGENT > NORMAL > LOW, then newest. Show the first.
        publish(raw.resolveActiveTickers().take(1))
    }

    fun restore(cached: List<TickerDisplayConfig>) {
        publish(cached.take(1))
    }

    fun clear() {
        publish(emptyList())
    }

    private fun publish(next: List<TickerDisplayConfig>) {
        val previous = _tickers.value
        if (previous == next) return
        _tickers.value = next
        if (next.isEmpty()) {
            TickerLogger.cleared()
        } else {
            TickerLogger.resolved(next)
        }
        Log.i(
            TAG,
            "overlay_tickers count=${next.size} id=${next.firstOrNull()?.id.orEmpty()}"
        )
    }

    companion object {
        private const val TAG = "OrionTicker"
    }
}

fun LayoutInfo?.hasTickerZone(): Boolean =
    this?.zones?.any { it.type == ZoneType.TICKER } == true
