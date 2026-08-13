package com.orion.player.data.recovery

import android.util.Log

/**
 * Structured auto-start / boot / recovery events.
 * Filter: adb logcat -s OrionAutoStart
 *
 * Every message starts with a stable UPPER_SNAKE event token so boot behaviour can
 * be verified from a device log without reading the surrounding text.
 */
object AutoStartLogger {
    private const val TAG = "OrionAutoStart"

    fun bootReceived(action: String, deferred: Boolean) {
        if (deferred) {
            Log.i(
                TAG,
                "BOOT_RECEIVED action=$action\n" +
                    "Locked boot — deferring launch until the user storage is unlocked"
            )
        } else {
            Log.i(TAG, "BOOT_RECEIVED action=$action\nLaunching Orion Player")
        }
    }

    fun bootDuplicateIgnored(action: String) {
        Log.i(TAG, "BOOT_DUPLICATE_IGNORED action=$action (boot already handled)")
    }

    fun autoLaunchStart(source: String, attempt: Int = 1) {
        Log.i(TAG, "PLAYER_AUTO_LAUNCH_START source=$source attempt=$attempt")
    }

    fun autoLaunchSuccess(source: String) {
        Log.i(TAG, "PLAYER_AUTO_LAUNCH_SUCCESS source=$source")
    }

    fun autoLaunchDispatchFailed(source: String, error: Throwable) {
        Log.w(TAG, "PLAYER_AUTO_LAUNCH_FAILED source=$source error=${error.javaClass.simpleName}: ${error.message}")
    }

    /**
     * The activity never became visible after every retry. On Android 10+ a background
     * activity start is silently dropped unless the app is the default Home app, a device
     * owner, or otherwise exempt — see docs/AUTO_START_PROVISIONING.md.
     */
    fun autoLaunchBlocked(attempts: Int) {
        Log.e(
            TAG,
            "PLAYER_AUTO_LAUNCH_BLOCKED attempts=$attempts\n" +
                "The player did not reach the foreground after boot. Android blocked the " +
                "background Activity start (BAL). Display over other apps / SYSTEM_ALERT_WINDOW " +
                "does not override this on Android 14+. Set Orion Player as the default Home " +
                "app (press Home → Orion Player → Always) or provision it as device owner " +
                "(docs/AUTO_START_PROVISIONING.md). The foreground service, sync and cache " +
                "keep running regardless."
        )
    }

    fun playerAlreadyRunning(source: String) {
        Log.i(TAG, "PLAYER_ALREADY_RUNNING source=$source (no duplicate launch)")
    }

    fun playerInitStart(source: String) {
        Log.i(TAG, "PLAYER_INIT_START source=$source")
    }

    fun bootRecovery(source: String) {
        Log.i(TAG, "BOOT_RECOVERY source=$source (resuming after device boot)")
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
        Log.i(
            TAG,
            "KIOSK_MODE_NOT_AVAILABLE reason=$reason — running as a normal app " +
                "(see docs/AUTO_START_PROVISIONING.md to provision lock task mode)"
        )
    }

    fun homeAppStatus(isDefaultHome: Boolean) {
        Log.i(TAG, "HOME_APP_STATUS defaultHome=$isDefaultHome")
    }

    fun launchPrivileges(
        sdk: Int,
        overlayGranted: Boolean,
        defaultHome: Boolean,
        deviceOwner: Boolean,
        fullScreenIntentAllowed: Boolean
    ) {
        Log.i(
            TAG,
            "BOOT_LAUNCH_PRIVILEGES sdk=$sdk overlayGranted=$overlayGranted " +
                "defaultHome=$defaultHome deviceOwner=$deviceOwner " +
                "fullScreenIntentAllowed=$fullScreenIntentAllowed"
        )
        if (defaultHome || deviceOwner) {
            Log.i(TAG, "BOOT_LAUNCH_PATH home-or-device-owner (background start should be allowed)")
            return
        }
        if (overlayGranted) {
            Log.w(
                TAG,
                "BOOT_LAUNCH_PATH overlay-only — Display over other apps does NOT exempt " +
                    "startActivity() from background-activity-launch (BAL) on Android 14+. " +
                    "Set Orion Player as the Home app, or provision it as device owner."
            )
        } else {
            Log.w(
                TAG,
                "BOOT_LAUNCH_PATH none — this device will likely block the boot Activity. " +
                    "Set Orion Player as the Home app (press Home → Orion Player → Always) " +
                    "or provision device owner. See docs/AUTO_START_PROVISIONING.md"
            )
        }
    }
}
