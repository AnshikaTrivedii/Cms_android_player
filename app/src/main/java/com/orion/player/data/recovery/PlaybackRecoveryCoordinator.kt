package com.orion.player.data.recovery

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between the watchdog foreground service and [PlaybackViewModel].
 * Remembers a pending restart request so a ViewModel that registers after
 * Activity relaunch can still recover from a stall.
 */
@Singleton
class PlaybackRecoveryCoordinator @Inject constructor() {

    @Volatile
    private var playbackRestartHandler: ((String) -> Unit)? = null

    @Volatile
    private var pendingRestartReason: String? = null

    fun registerPlaybackRestartHandler(handler: (String) -> Unit) {
        playbackRestartHandler = handler
        pendingRestartReason?.let { reason ->
            pendingRestartReason = null
            handler(reason)
        }
    }

    fun unregisterPlaybackRestartHandler() {
        playbackRestartHandler = null
    }

    fun requestPlaybackRestart(reason: String): Boolean {
        val handler = playbackRestartHandler
        if (handler != null) {
            handler(reason)
            return true
        }
        pendingRestartReason = reason
        return false
    }
}
