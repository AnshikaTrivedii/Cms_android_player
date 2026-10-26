package com.orion.player.data.recovery

import android.util.Log

/**
 * Structured player startup and recovery events.
 * Filter: adb logcat -s OrionAutoStart
 *
 * Every message starts with a stable UPPER_SNAKE event token so recovery behaviour can
 * be verified from a device log without reading the surrounding text.
 */
object AutoStartLogger {
    private const val TAG = "OrionAutoStart"

    fun autoLaunchStart(source: String, attempt: Int = 1) {
        Log.i(TAG, "PLAYER_AUTO_LAUNCH_START source=$source attempt=$attempt")
    }

    fun autoLaunchSuccess(source: String) {
        Log.i(TAG, "PLAYER_AUTO_LAUNCH_SUCCESS source=$source")
    }

    fun autoLaunchDispatchFailed(source: String, error: Throwable) {
        Log.w(TAG, "PLAYER_AUTO_LAUNCH_FAILED source=$source error=${error.javaClass.simpleName}: ${error.message}")
    }

    fun playerAlreadyRunning(source: String) {
        Log.i(TAG, "PLAYER_ALREADY_RUNNING source=$source (no duplicate launch)")
    }

    fun playerInitStart(source: String) {
        Log.i(TAG, "PLAYER_INIT_START source=$source")
    }

    fun processRecovery(previousSessionAgeMs: Long) {
        Log.w(
            TAG,
            "PLAYER_PROCESS_RECOVERY previous session ended without a clean stop " +
                "(${previousSessionAgeMs}ms ago) — restoring from cache"
        )
    }

    fun cachePlaybackStart(playlistId: String?, playlistName: String?, assetCount: Int) {
        Log.i(
            TAG,
            "CACHE_PLAYBACK_START\n" +
                "Playlist: ${playlistId?.takeIf { it.isNotBlank() } ?: "unknown"}\n" +
                "Name: ${playlistName?.takeIf { it.isNotBlank() } ?: "unnamed"}\n" +
                "Assets: $assetCount"
        )
    }

    fun cachePlaybackUnavailable(reason: String) {
        Log.i(TAG, "CACHE_PLAYBACK_UNAVAILABLE reason=$reason")
    }

    fun cmsSyncStart() {
        Log.i(TAG, "CMS_SYNC_START")
    }

    fun cmsSyncSuccess(outcome: String) {
        Log.i(TAG, "CMS_SYNC_SUCCESS outcome=$outcome")
    }

    fun cmsSyncFailed(reason: String, playingFromCache: Boolean) {
        Log.w(
            TAG,
            "CMS_SYNC_FAILED reason=$reason cachedPlaybackContinues=$playingFromCache"
        )
    }

    fun watchdogRecovery(reason: String, attempt: Int) {
        Log.w(TAG, "WATCHDOG_RECOVERY reason=$reason attempt=$attempt")
    }

    fun watchdogRecoveryDeferred(reason: String, retryInMs: Long, attempts: Int) {
        Log.i(
            TAG,
            "WATCHDOG_RECOVERY_DEFERRED reason=$reason attempts=$attempts " +
                "nextAttemptInMs=$retryInMs (backoff, avoiding a restart loop)"
        )
    }

    fun crashLoopBackoff(attempts: Int, retryInMs: Long) {
        Log.e(
            TAG,
            "CRASH_LOOP_BACKOFF crashes=$attempts nextRelaunchInMs=$retryInMs " +
                "— skipping the immediate relaunch to break the crash loop; cached content is preserved"
        )
    }

    fun kioskModeEntered(deviceOwner: Boolean) {
        Log.i(TAG, "KIOSK_MODE_ENTERED lockTaskPermitted=true deviceOwner=$deviceOwner")
    }

    fun kioskModeNotAvailable(reason: String) {
        Log.i(TAG, "KIOSK_MODE_NOT_AVAILABLE reason=$reason — running as a normal app")
    }
}
