package com.orion.player.data.recovery

import android.content.Context

/**
 * Exponential backoff for every automatic recovery action (crash relaunch, watchdog
 * activity restart, watchdog playback restart).
 *
 * Without this, a component that fails immediately after every restart produces a tight
 * loop: crash -> relaunch -> crash. Attempts are persisted in device-protected storage so
 * the backoff survives the process death that a crash relaunch causes.
 *
 * The attempt counter resets only when the *failure* has not been observed for
 * [QUIET_RESET_MS] — deliberately not "no attempt for a while", because a persistent
 * failure spends most of its time being denied. Keying the reset on attempts would let a
 * capped backoff repeatedly heal itself and restart the escalation from zero, turning the
 * backoff into a permanent sawtooth. Callers that can see the player is healthy call
 * [reset] directly, which restores instant recovery.
 */
class RecoveryThrottle(private val store: BootStateStore) {

    data class Decision(
        val allowed: Boolean,
        val attempt: Int,
        val retryInMs: Long
    )

    fun evaluate(key: String, nowMs: Long = System.currentTimeMillis()): Decision {
        val lastObservedAt = store.lastObservationAt(key)
        val healed = lastObservedAt <= 0L || elapsedSince(lastObservedAt, nowMs) >= QUIET_RESET_MS
        store.recordObservation(key, nowMs)

        if (healed) {
            store.recordAttempt(key, 1, nowMs)
            return Decision(allowed = true, attempt = 1, retryInMs = backoffFor(1))
        }

        val attempts = store.attemptCount(key)
        val lastAttemptAt = store.lastAttemptAt(key)
        val requiredGap = backoffFor(attempts)
        val elapsed = if (lastAttemptAt <= 0L) Long.MAX_VALUE else elapsedSince(lastAttemptAt, nowMs)
        if (elapsed < requiredGap) {
            return Decision(allowed = false, attempt = attempts, retryInMs = requiredGap - elapsed)
        }

        val attempt = attempts + 1
        store.recordAttempt(key, attempt, nowMs)
        return Decision(allowed = true, attempt = attempt, retryInMs = backoffFor(attempt))
    }

    fun reset(key: String) = store.resetAttempts(key)

    /** 15s, 30s, 60s, 120s, 240s, 480s, then capped at 15 min. */
    private fun backoffFor(attempts: Int): Long {
        if (attempts <= 0) return 0L
        val scaled = BASE_BACKOFF_MS shl (attempts - 1).coerceAtMost(MAX_SHIFT)
        return scaled.coerceAtMost(MAX_BACKOFF_MS)
    }

    /** A backwards clock jump (no RTC battery, NTP correction) must never block recovery. */
    private fun elapsedSince(timestampMs: Long, nowMs: Long): Long {
        val elapsed = nowMs - timestampMs
        return if (elapsed < 0L) Long.MAX_VALUE else elapsed
    }

    companion object {
        const val KEY_CRASH = "crash_relaunch"
        const val KEY_ACTIVITY_RESTART = "watchdog_activity"
        const val KEY_PLAYBACK_RESTART = "watchdog_playback"

        private const val BASE_BACKOFF_MS = 15_000L
        private const val MAX_BACKOFF_MS = 900_000L
        private const val MAX_SHIFT = 6
        private const val QUIET_RESET_MS = 900_000L

        fun from(context: Context): RecoveryThrottle =
            RecoveryThrottle(BootStateStore.from(context))
    }
}
