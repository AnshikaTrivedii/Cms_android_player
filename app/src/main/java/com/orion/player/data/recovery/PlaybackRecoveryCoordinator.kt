package com.orion.player.data.recovery

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between the watchdog foreground service and [PlaybackViewModel].
 */
@Singleton
class PlaybackRecoveryCoordinator @Inject constructor() {

    @Volatile
    private var playbackRestartHandler: ((String) -> Unit)? = null

    fun registerPlaybackRestartHandler(handler: (String) -> Unit) {
        playbackRestartHandler = handler
    }

    fun unregisterPlaybackRestartHandler() {
        playbackRestartHandler = null
    }

    fun requestPlaybackRestart(reason: String): Boolean {
        val handler = playbackRestartHandler ?: return false
        handler(reason)
        return true
    }
}
