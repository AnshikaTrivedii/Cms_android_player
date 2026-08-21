package com.orion.player.data.sync

import android.util.Log
import com.orion.player.data.local.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RevisionPollIntervalConfig @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    private val _intervalSeconds = MutableStateFlow(initialInterval())
    val intervalSeconds: StateFlow<Int> = _intervalSeconds.asStateFlow()

    val isEnabled: Boolean get() = _intervalSeconds.value > 0

    init {
        if (securePrefs.revisionPollIntervalSeconds != _intervalSeconds.value) {
            securePrefs.revisionPollIntervalSeconds = _intervalSeconds.value
        }
    }

    fun intervalMs(): Long = _intervalSeconds.value.toLong() * 1000L

    /**
     * @return true when the effective interval changed.
     * `0` disables `/sync-revision` polling. Null means the server omitted the field.
     */
    fun updateInterval(seconds: Int?): Boolean {
        if (seconds == null) return false
        val clamped = clamp(seconds)
        if (clamped == _intervalSeconds.value) return false
        securePrefs.revisionPollIntervalSeconds = clamped
        _intervalSeconds.value = clamped
        Log.i(TAG, "Revision poll interval updated to ${clamped}s (0 = disabled)")
        return true
    }

    private fun initialInterval(): Int {
        val stored = securePrefs.revisionPollIntervalSeconds
        // Previous client used 5s (or a 5-minute floor). Treat those stored defaults as unset.
        if (stored == LEGACY_FAST_DEFAULT_SECONDS || stored == LEGACY_DEFAULT_SECONDS) {
            return DEFAULT_SECONDS
        }
        return clamp(stored)
    }

    companion object {
        private const val TAG = "OrionSync"
        /** Server default: do not poll /sync-revision; heartbeat delivers FORCE_SYNC. */
        const val DEFAULT_SECONDS = 0
        const val MAX_SECONDS = 60 * 60
        private const val LEGACY_FAST_DEFAULT_SECONDS = 5
        private const val LEGACY_DEFAULT_SECONDS = 5 * 60

        private fun clamp(seconds: Int): Int {
            if (seconds <= 0) return 0
            return seconds.coerceAtMost(MAX_SECONDS)
        }
    }
}
