package com.orion.player.util

import com.orion.player.data.local.SecurePrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prevents authenticated API calls before the device is fully paired.
 */
@Singleton
class SessionGuard @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    fun requirePairedToken(): String {
        val token = securePrefs.getBearerToken()
        require(!token.isNullOrBlank()) { "Device not paired — skipping authenticated API call" }
        return token
    }

    fun isPairedWithToken(): Boolean =
        securePrefs.isPaired && !securePrefs.deviceToken.isNullOrBlank()
}
