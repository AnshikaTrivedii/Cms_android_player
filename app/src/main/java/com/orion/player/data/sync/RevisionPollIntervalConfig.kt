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

    init {
        if (securePrefs.revisionPollIntervalSeconds != _intervalSeconds.value) {
            securePrefs.revisionPollIntervalSeconds = _intervalSeconds.value
        }
    }

    fun intervalMs(): Long = _intervalSeconds.value.toLong() * 1000L

    fun updateInterval(seconds: Int?): Boolean {
        val clamped = clamp(seconds)
        if (clamped == _intervalSeconds.value) return false
        securePrefs.revisionPollIntervalSeconds = clamped
        _intervalSeconds.value = clamped
        Log.i(TAG, "Revision poll interval updated to ${clamped}s")
        return true
    }

    private fun initialInterval(): Int {
        val stored = securePrefs.revisionPollIntervalSeconds
        // Previous client floored this at 5 minutes; treat that stored default as unset.
        if (stored == LEGACY_DEFAULT_SECONDS) return DEFAULT_SECONDS
        return clamp(stored)
    }

    companion object {
        private const val TAG = "OrionSync"
        const val DEFAULT_SECONDS = 5
        const val MIN_SECONDS = 5
        const val MAX_SECONDS = 60 * 60
        private const val LEGACY_DEFAULT_SECONDS = 5 * 60

        private fun clamp(seconds: Int?): Int =
            (seconds ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }
}
