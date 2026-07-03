package com.orion.player.data.recovery

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide playback, activity, and renderer health signals for the watchdog.
 */
@Singleton
class PlayerHealthMonitor @Inject constructor() {

    @Volatile var isActivityAlive: Boolean = false
        private set

    @Volatile var isPlaybackActive: Boolean = false
        private set

    @Volatile var isPlaybackExpected: Boolean = false
        private set

    @Volatile var isVideoSlotActive: Boolean = false
        private set

    @Volatile var isVideoRendererAlive: Boolean = false
        private set

    @Volatile var lastPlaybackPulseMs: Long = 0L
        private set

    @Volatile var lastVideoRendererPulseMs: Long = 0L
        private set

    @Volatile var lastQueueProgressMs: Long = 0L
        private set

    @Volatile var lastQueueIndex: Int = -1
        private set

    @Volatile var currentSlotConfiguredMs: Long = 0L
        private set

    @Volatile var currentSlotStartMs: Long = 0L
        private set

    @Volatile var startupInitMs: Long = 0L
        private set

    @Volatile var currentAssetName: String? = null
        private set

    @Volatile var currentPlaylistName: String? = null
        private set

    @Volatile var currentQueueSize: Int = 0
        private set

    private var slotLoopAliveChecker: (() -> Boolean)? = null

    fun registerSlotLoopAliveChecker(checker: () -> Boolean) {
        slotLoopAliveChecker = checker
    }

    fun recordActivityResumed() {
        isActivityAlive = true
        pulsePlayback()
    }

    fun recordActivityPaused() {
        isActivityAlive = false
    }

    fun recordStartupInit() {
        startupInitMs = System.currentTimeMillis()
        pulsePlayback()
    }

    fun recordPlaybackExpected(expected: Boolean) {
        isPlaybackExpected = expected
    }

    fun recordPlaybackActive(active: Boolean) {
        isPlaybackActive = active
        if (active) {
            pulsePlayback()
        }
    }

    fun recordVideoSlotActive(active: Boolean) {
        isVideoSlotActive = active
        if (!active) {
            isVideoRendererAlive = false
        }
    }

    fun recordVideoRendererPulse() {
        isVideoRendererAlive = true
        lastVideoRendererPulseMs = System.currentTimeMillis()
        pulsePlayback()
    }

    fun recordQueueProgress(index: Int) {
        if (index != lastQueueIndex) {
            lastQueueIndex = index
            lastQueueProgressMs = System.currentTimeMillis()
            pulsePlayback()
        }
    }

    fun recordPlaybackContext(assetName: String?, playlistName: String?, queueSize: Int) {
        currentAssetName = assetName
        currentPlaylistName = playlistName
        currentQueueSize = queueSize
    }

    fun recordSlotStarted(configuredMs: Long) {
        currentSlotConfiguredMs = configuredMs
        currentSlotStartMs = System.currentTimeMillis()
        pulsePlayback()
    }

    fun recordSlotEnded() {
        currentSlotConfiguredMs = 0L
        currentSlotStartMs = 0L
        pulsePlayback()
    }

    fun pulsePlayback() {
        lastPlaybackPulseMs = System.currentTimeMillis()
    }

    fun isSlotLoopAlive(): Boolean = slotLoopAliveChecker?.invoke() ?: false

    fun lastPulseAgeMs(): Long {
        val last = lastPlaybackPulseMs
        if (last == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    fun queueProgressAgeMs(): Long {
        val last = lastQueueProgressMs
        if (last == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    fun usedMemoryMb(): Long =
        ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024))

    fun maxMemoryMb(): Long =
        (Runtime.getRuntime().maxMemory() / (1024 * 1024))

    fun isVideoRendererStale(): Boolean {
        if (!isVideoSlotActive || !isPlaybackActive) return false
        if (lastVideoRendererPulseMs == 0L) {
            val slotAge = System.currentTimeMillis() - currentSlotStartMs
            return slotAge > PlayerRuntimeConfig.VIDEO_RENDERER_START_TIMEOUT_MS
        }
        val age = System.currentTimeMillis() - lastVideoRendererPulseMs
        return age > PlayerRuntimeConfig.VIDEO_RENDERER_STUCK_MS
    }

    fun isPlaybackStuck(): Boolean {
        if (!isPlaybackExpected) return false

        val now = System.currentTimeMillis()
        val pulseAge = lastPulseAgeMs()

        if (!isPlaybackActive) {
            val startupAge = if (startupInitMs > 0L) now - startupInitMs else 0L
            return startupAge > PlayerRuntimeConfig.STARTUP_STUCK_TIMEOUT_MS
        }

        if (pulseAge > PlayerRuntimeConfig.PLAYBACK_STUCK_TIMEOUT_MS) {
            return true
        }

        if (!isSlotLoopAlive()) {
            return true
        }

        if (isVideoRendererStale()) {
            return true
        }

        if (currentSlotStartMs > 0L) {
            val slotAge = now - currentSlotStartMs
            if (currentSlotConfiguredMs > 0L) {
                val maxSlotMs = currentSlotConfiguredMs * 2 +
                    PlayerRuntimeConfig.SLOT_STUCK_GRACE_MS
                if (slotAge > maxSlotMs) return true
            } else if (slotAge > 3_600_000L) {
                return true
            }

            if (lastQueueProgressMs > 0L) {
                val progressAge = now - lastQueueProgressMs
                val queueStuckLimit = (currentSlotConfiguredMs.takeIf { it > 0L }
                    ?: PlayerRuntimeConfig.PLAYBACK_STUCK_TIMEOUT_MS) +
                    PlayerRuntimeConfig.SLOT_STUCK_GRACE_MS
                if (progressAge > queueStuckLimit && slotAge > queueStuckLimit) {
                    return true
                }
            }
        }

        return false
    }
}
