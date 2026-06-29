package com.orion.player.data.sync

/**
 * Temporary product flag — multi-zone layout playback is disabled.
 * Sync may still receive [layout] from the API; the player ignores it and uses playlist-only mode.
 */
object PlayerPlaybackConfig {
    const val LAYOUT_PLAYBACK_ENABLED = false
}
