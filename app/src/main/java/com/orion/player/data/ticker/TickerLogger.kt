package com.orion.player.data.ticker

import android.util.Log

/**
 * Structured logging for ticker sync and render lifecycle.
 */
object TickerLogger {
    private const val TAG = "OrionTicker"

    fun received(tickers: List<TickerInfo>) {
        Log.i(TAG, "Ticker received: count=${tickers.size}")
        tickers.forEach { ticker ->
            Log.i(
                TAG,
                "Ticker received: id=${ticker.id} scope=${ticker.scope} " +
                    "priority=${ticker.priority} text=${ticker.text.take(80)}"
            )
        }
    }

    fun resolved(tickers: List<TickerDisplayConfig>) {
        Log.i(TAG, "Ticker resolved for device: count=${tickers.size}")
        tickers.forEach { ticker ->
            Log.i(
                TAG,
                "Ticker resolved: id=${ticker.id} scope=${ticker.scope.name} " +
                    "priority=${ticker.priority.name} text=${ticker.text.take(80)}"
            )
        }
    }

    fun rendered(config: TickerDisplayConfig) {
        Log.i(
            TAG,
            "Ticker rendered: id=${config.id} scope=${config.scope.name} " +
                "text=${config.text.take(80)}"
        )
    }

    fun cleared() {
        Log.i(TAG, "Ticker cleared: no active tickers from sync")
    }
}
