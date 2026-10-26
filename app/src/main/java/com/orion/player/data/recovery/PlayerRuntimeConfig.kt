package com.orion.player.data.recovery

/**
 * Runtime configuration for kiosk mode, foreground protection, and self-recovery.
 */
object PlayerRuntimeConfig {
    /** Default kiosk mode for production signage deployments. */
    const val KIOSK_MODE_ENABLED_DEFAULT = true

    /** Watchdog health-check interval. */
    const val WATCHDOG_INTERVAL_MS = 30_000L

    /** No playback pulse for this long → treat as stuck. */
    const val PLAYBACK_STUCK_TIMEOUT_MS = 120_000L

    /** Loading/waiting without content for this long → restart playback. */
    const val STARTUP_STUCK_TIMEOUT_MS = 90_000L

    /** Grace period after slot start before stuck-by-duration checks apply. */
    const val SLOT_STUCK_GRACE_MS = 30_000L

    /** Video renderer must report within this time after slot start. */
    const val VIDEO_RENDERER_START_TIMEOUT_MS = 45_000L

    /** No video renderer pulse for this long during video playback → stuck. */
    const val VIDEO_RENDERER_STUCK_MS = 60_000L

    /** No Proof-of-Play generated while playback is expected for this long → stall. */
    const val POP_STALL_TIMEOUT_MS = 600_000L

    /** Log memory usage every N watchdog checks (30s each). 20 = ~10 minutes. */
    const val MEMORY_LOG_EVERY_N_CHECKS = 20

    /** Full stability report interval. */
    const val STABILITY_REPORT_INTERVAL_MS = 600_000L
}
