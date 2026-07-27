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
    private val _intervalSeconds = MutableStateFlow(securePrefs.revisionPollIntervalSeconds)
    val intervalSeconds: StateFlow<Int> = _intervalSeconds.asStateFlow()

    fun intervalMs(): Long = _intervalSeconds.value.toLong() * 1000L

    fun updateInterval(seconds: Int?): Boolean {
        val clamped = (seconds ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
        if (clamped == _intervalSeconds.value) return false
        securePrefs.revisionPollIntervalSeconds = clamped
        _intervalSeconds.value = clamped
        Log.i(TAG, "Revision poll interval updated to ${clamped}s")
        return true
    }

    companion object {
        private const val TAG = "OrionSync"
        const val DEFAULT_SECONDS = 5
        const val MIN_SECONDS = 3
        const val MAX_SECONDS = 60
    }
}
