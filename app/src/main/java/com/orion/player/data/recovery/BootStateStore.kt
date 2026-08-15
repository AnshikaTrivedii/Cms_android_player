package com.orion.player.data.recovery

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Small boot/recovery state kept in **device-protected storage** so it can be read and
 * written before the user unlocks the device (direct boot) and by the crash handler.
 *
 * Deliberately plain [SharedPreferences] with no Hilt and no encryption: it holds no
 * credentials or content, only timers and counters. Credentials stay in [SecurePrefs]
 * (credential-protected storage) and are never touched before unlock.
 */
class BootStateStore private constructor(private val prefs: SharedPreferences) {

    // ── Boot handling ──────────────────────────────────────────────

    /**
     * Several broadcasts describe one physical boot (BOOT_COMPLETED plus the OEM
     * quick-boot variants), so only the first one inside the window starts the player.
     * Locked boot is tracked separately and never consumes this window, otherwise the
     * real BOOT_COMPLETED that follows unlock would be swallowed.
     *
     * Both clocks must agree that this is the same boot:
     * - wall clock alone is unsafe because signage boxes without an RTC battery come up
     *   at epoch 0 and jump forward once NTP answers, which can make a real boot look
     *   like it happened *before* the previous one;
     * - uptime alone is unsafe because it resets on every boot, so a reboot can land in
     *   the same window as the previous boot's broadcast.
     * A negative delta on either clock therefore means "new boot", never "duplicate".
     */
    fun shouldHandleBoot(
        nowMs: Long = System.currentTimeMillis(),
        uptimeMs: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val lastAt = prefs.getLong(KEY_LAST_BOOT_AT, 0L)
        val lastUptime = prefs.getLong(KEY_LAST_BOOT_UPTIME, -1L)
        if (lastAt <= 0L || lastUptime < 0L) return true
        val wallDelta = nowMs - lastAt
        val uptimeDelta = uptimeMs - lastUptime
        val sameBoot = uptimeDelta in 0 until BOOT_DEDUPE_WINDOW_MS &&
            wallDelta in 0 until BOOT_DEDUPE_WINDOW_MS
        return !sameBoot
    }

    fun recordHandledBoot(
        action: String,
        nowMs: Long = System.currentTimeMillis(),
        uptimeMs: Long = SystemClock.elapsedRealtime()
    ) {
        prefs.edit()
            .putString(KEY_LAST_BOOT_ACTION, action)
            .putLong(KEY_LAST_BOOT_AT, nowMs)
            .putLong(KEY_LAST_BOOT_UPTIME, uptimeMs)
            .apply()
    }

    fun recordLockedBoot(action: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_LOCKED_BOOT_ACTION, action)
            .putLong(KEY_LAST_LOCKED_BOOT_AT, nowMs)
            .apply()
    }

    // ── Auto-launch retry ──────────────────────────────────────────

    val bootLaunchPending: Boolean
        get() = prefs.getBoolean(KEY_BOOT_LAUNCH_PENDING, false)

    val bootLaunchAttempts: Int
        get() = prefs.getInt(KEY_BOOT_LAUNCH_ATTEMPTS, 0)

    fun markBootLaunchPending() {
        prefs.edit()
            .putBoolean(KEY_BOOT_LAUNCH_PENDING, true)
            .putInt(KEY_BOOT_LAUNCH_ATTEMPTS, 0)
            .apply()
    }

    fun recordBootLaunchAttempt(): Int {
        val next = bootLaunchAttempts + 1
        prefs.edit().putInt(KEY_BOOT_LAUNCH_ATTEMPTS, next).apply()
        return next
    }

    fun clearBootLaunchPending() {
        prefs.edit()
            .putBoolean(KEY_BOOT_LAUNCH_PENDING, false)
            .putInt(KEY_BOOT_LAUNCH_ATTEMPTS, 0)
            .apply()
    }

    /**
     * True when the Home-role picker has not been shown yet on this boot.
     * A stored uptime larger than the current uptime means the previous prompt
     * belonged to an earlier boot (uptime reset).
     */
    fun shouldPromptHomeRole(uptimeMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val promptedUptime = prefs.getLong(KEY_HOME_ROLE_PROMPTED_UPTIME, -1L)
        if (promptedUptime < 0L) return true
        return promptedUptime > uptimeMs
    }

    fun markHomeRolePrompted(uptimeMs: Long = SystemClock.elapsedRealtime()) {
        prefs.edit().putLong(KEY_HOME_ROLE_PROMPTED_UPTIME, uptimeMs).apply()
    }

    // ── Process liveness (process-death detection) ─────────────────

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

    // ── Recovery attempt counters (crash loop / watchdog backoff) ──

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
        private const val PREFS_NAME = "orion_boot_state"
        private const val KEY_LAST_BOOT_ACTION = "last_boot_action"
        private const val KEY_LAST_BOOT_AT = "last_boot_at"
        private const val KEY_LAST_BOOT_UPTIME = "last_boot_uptime"
        private const val KEY_LAST_LOCKED_BOOT_ACTION = "last_locked_boot_action"
        private const val KEY_LAST_LOCKED_BOOT_AT = "last_locked_boot_at"
        private const val KEY_BOOT_LAUNCH_PENDING = "boot_launch_pending"
        private const val KEY_BOOT_LAUNCH_ATTEMPTS = "boot_launch_attempts"
        private const val KEY_HOME_ROLE_PROMPTED_UPTIME = "home_role_prompted_uptime"
        private const val KEY_PLAYER_RUNNING = "player_running"
        private const val KEY_PLAYER_RUNNING_AT = "player_running_at"
        private const val KEY_ATTEMPTS_PREFIX = "attempts_"
        private const val KEY_ATTEMPT_AT_PREFIX = "attempt_at_"
        private const val KEY_OBSERVED_AT_PREFIX = "observed_at_"

        private const val BOOT_DEDUPE_WINDOW_MS = 60_000L

        @Volatile
        private var instance: BootStateStore? = null

        fun from(context: Context): BootStateStore {
            return instance ?: synchronized(this) {
                instance ?: BootStateStore(devicePrefs(context)).also { instance = it }
            }
        }

        private fun devicePrefs(context: Context): SharedPreferences {
            // Device-protected storage is readable before the user unlocks the device.
            val storageContext = context.applicationContext.createDeviceProtectedStorageContext()
                ?: context.applicationContext
            return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
