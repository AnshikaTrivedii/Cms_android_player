package com.orion.player.data.recovery

import android.util.Log

/**
 * Digital signage recovery diagnostics.
 * Filter: adb logcat -s OrionRecovery
 */
object OrionRecoveryLogger {
    private const val TAG = "OrionRecovery"

    fun logDeviceBootDetected(action: String) {
        Log.i(TAG, "Device boot detected: action=$action")
    }

    fun logBootReceiverTriggered(action: String) {
        Log.i(TAG, "Boot receiver triggered: action=$action")
    }

    fun logPlayerStarted(source: String) {
        Log.i(TAG, "Player started: source=$source")
    }

    fun logForegroundServiceStarted() {
        Log.i(TAG, "Foreground service started")
    }

    fun logForegroundServiceRestart(reason: String) {
        Log.w(TAG, "Foreground service restart: reason=$reason")
    }

    fun logWatchdogStarted() {
        Log.i(TAG, "Watchdog started: intervalMs=${PlayerRuntimeConfig.WATCHDOG_INTERVAL_MS}")
    }

    fun logCachedPlaylistLoaded(assetCount: Int, playlistName: String) {
        Log.i(
            TAG,
            "Cached playlist loaded: assets=$assetCount playlist=$playlistName"
        )
    }

    fun logPlaybackStarted(source: String) {
        Log.i(TAG, "Playback started: source=$source")
    }

    fun logBackgroundSyncStarted(reason: String) {
        Log.i(TAG, "Background sync started: reason=$reason")
    }

    fun logCrashDetected(throwable: Throwable) {
        Log.e(
            TAG,
            "Crash detected: ${throwable.javaClass.simpleName}: ${throwable.message}",
            throwable
        )
    }

    fun logRecoveryCompleted(reason: String) {
        Log.i(TAG, "Recovery completed: reason=$reason")
    }

    fun logWatchdogCheck(
        activityAlive: Boolean,
        playbackActive: Boolean,
        playbackExpected: Boolean,
        videoRendererAlive: Boolean,
        queueProgressAgeMs: Long,
        slotLoopAlive: Boolean,
        lastPulseAgeMs: Long
    ) {
        Log.d(
            TAG,
            "Watchdog check: activityAlive=$activityAlive playbackActive=$playbackActive " +
                "playbackExpected=$playbackExpected videoRendererAlive=$videoRendererAlive " +
                "queueProgressAgeMs=$queueProgressAgeMs slotLoopAlive=$slotLoopAlive " +
                "lastPulseAgeMs=$lastPulseAgeMs"
        )
    }

    fun logActivityRestart(reason: String) {
        Log.w(TAG, "Activity restart: reason=$reason")
    }

    fun logPlaybackRestart(reason: String) {
        Log.w(TAG, "Playback restart: reason=$reason")
    }

    fun logKioskModeEnabled(enabled: Boolean) {
        Log.i(TAG, "Kiosk mode: enabled=$enabled")
    }

    fun logMemoryStatus(usedMb: Long, maxMb: Long) {
        Log.i(TAG, "Memory status: usedMb=$usedMb maxMb=$maxMb")
    }
}
