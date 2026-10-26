package com.orion.player.data.recovery

import android.content.Context
import android.content.SharedPreferences

/**
 * Small recovery state kept in device-protected storage so the crash handler can read
 * and update it independently of encrypted application preferences.
 *
 * It contains no credentials or content, only liveness markers and recovery counters.
 */
class BootStateStore private constructor(private val prefs: SharedPreferences) {

    // ── Process liveness (process-death detection) ────────────────

    /**
     * True when the previous process marked the player as running but never recorded a
     * clean stop — i.e. the process was killed or crashed.
     */
    fun consumeUncleanShutdown(nowMs: Long = System.currentTimeMillis()): Long? {
        if (!prefs.getBoolean(KEY_PLAYER_RUNNING, false)) return null
        val since = prefs.getLong(KEY_PLAYER_RUNNING_AT, nowMs)
        prefs.edit().putBoolean(KEY_PLAYER_RUNNING, false).apply()
        return (nowMs - since).coerceAtLeast(0L)
    }

    fun markPlayerRunning(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(KEY_PLAYER_RUNNING, true)
            .putLong(KEY_PLAYER_RUNNING_AT, nowMs)
            .apply()
    }

    /** Recorded when the Activity finishes on purpose, so it is not treated as a kill. */
    fun markPlayerStopped() {
        prefs.edit().putBoolean(KEY_PLAYER_RUNNING, false).apply()
    }

    // ── Recovery attempt counters (crash loop / watchdog backoff) ──────────

    fun attemptCount(key: String): Int = prefs.getInt("${KEY_ATTEMPTS_PREFIX}$key", 0)

    fun lastAttemptAt(key: String): Long = prefs.getLong("${KEY_ATTEMPT_AT_PREFIX}$key", 0L)

    /** Last time the failure was seen, whether or not a recovery was allowed. */
    fun lastObservationAt(key: String): Long = prefs.getLong("${KEY_OBSERVED_AT_PREFIX}$key", 0L)

    fun recordObservation(key: String, nowMs: Long) {
        prefs.edit().putLong("${KEY_OBSERVED_AT_PREFIX}$key", nowMs).apply()
    }

    fun recordAttempt(key: String, count: Int, nowMs: Long) {
        prefs.edit()
            .putInt("${KEY_ATTEMPTS_PREFIX}$key", count)
            .putLong("${KEY_ATTEMPT_AT_PREFIX}$key", nowMs)
            .apply()
    }

    fun resetAttempts(key: String) {
        if (attemptCount(key) == 0 && lastObservationAt(key) == 0L) return
        prefs.edit()
            .remove("${KEY_ATTEMPTS_PREFIX}$key")
            .remove("${KEY_ATTEMPT_AT_PREFIX}$key")
            .remove("${KEY_OBSERVED_AT_PREFIX}$key")
            .apply()
    }

    companion object {
        // Keep the existing preference file name so upgrades retain recovery throttles.
        private const val PREFS_NAME = "orion_boot_state"
        private const val KEY_PLAYER_RUNNING = "player_running"
        private const val KEY_PLAYER_RUNNING_AT = "player_running_at"
        private const val KEY_ATTEMPTS_PREFIX = "attempts_"
        private const val KEY_ATTEMPT_AT_PREFIX = "attempt_at_"
        private const val KEY_OBSERVED_AT_PREFIX = "observed_at_"

        @Volatile
        private var instance: BootStateStore? = null

        fun from(context: Context): BootStateStore {
            return instance ?: synchronized(this) {
                instance ?: BootStateStore(devicePrefs(context)).also { instance = it }
            }
        }

        private fun devicePrefs(context: Context): SharedPreferences {
            val storageContext = context.applicationContext.createDeviceProtectedStorageContext()
                ?: context.applicationContext
            return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
