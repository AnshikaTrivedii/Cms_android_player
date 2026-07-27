package com.orion.player.data.sync

import android.util.Log
import com.orion.player.data.local.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-driven interval between full [GET /player/sync] polls.
 * Updated from heartbeat and sync responses; persisted across restarts.
 */
@Singleton
class SyncIntervalConfig @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    private val _intervalSeconds = MutableStateFlow(securePrefs.syncIntervalSeconds)
    val intervalSeconds: StateFlow<Int> = _intervalSeconds.asStateFlow()

    fun intervalMs(): Long = _intervalSeconds.value.toLong() * 1000L

    /**
     * @return true when the effective interval changed.
     */
    fun updateInterval(seconds: Int?): Boolean {
        val clamped = (seconds ?: DEFAULT_SYNC_INTERVAL_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
        if (clamped == _intervalSeconds.value) return false
        securePrefs.syncIntervalSeconds = clamped
        _intervalSeconds.value = clamped
        Log.i(TAG, "Full sync interval updated to ${clamped}s")
        return true
    }

    companion object {
        private const val TAG = "OrionSync"
        const val DEFAULT_SYNC_INTERVAL_SECONDS = 120
        const val MIN_SECONDS = 30
        const val MAX_SECONDS = 3600
    }
}
