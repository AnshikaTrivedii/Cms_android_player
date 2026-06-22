package com.orion.player.data.sync

/**
 * Intervals for near-real-time content sync.
 * SSE provides instant updates; revision polling and full-sync fallback cover offline/SSE gaps.
 */
object SyncConfig {
    /** Lightweight revision check — small payload, safe to poll frequently. */
    const val REVISION_POLL_INTERVAL_MS = 5_000L

    /** Full sync fallback when revision endpoint and SSE are unavailable. */
    const val FULL_SYNC_POLL_INTERVAL_MS = 15_000L

    /** Minimum gap between full sync executions (debounce burst triggers). */
    const val MIN_SYNC_DEBOUNCE_MS = 2_000L

    /** SSE reconnect backoff. */
    const val SSE_RECONNECT_BASE_MS = 3_000L
    const val SSE_RECONNECT_MAX_MS = 60_000L
}
