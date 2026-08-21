package com.orion.player.data.sync

/**
 * Intervals for content sync.
 * Heartbeat delivers FORCE_SYNC; revision polling is off by default.
 * Full-sync fallback covers missed commands.
 */
object SyncConfig {
    /** Disabled by default — heartbeat is the primary change detector. */
    const val REVISION_POLL_INTERVAL_MS = 0L

    /** Full sync fallback when heartbeat FORCE_SYNC is missed. */
    @Deprecated("Use SyncIntervalConfig — server-driven, default 600s")
    const val FULL_SYNC_POLL_INTERVAL_MS = 600_000L

    /** Default full sync interval when the server omits syncIntervalSeconds. */
    const val DEFAULT_SYNC_INTERVAL_SECONDS = 600

    /** Default revision poll interval when the server omits revisionPollIntervalSeconds. 0 = disabled. */
    const val DEFAULT_REVISION_POLL_INTERVAL_SECONDS = 0

    /** Minimum gap between full sync executions (debounce burst triggers). */
    const val MIN_SYNC_DEBOUNCE_MS = 2_000L

    /** SSE reconnect backoff. */
    const val SSE_RECONNECT_BASE_MS = 3_000L
    const val SSE_RECONNECT_MAX_MS = 60_000L
}
