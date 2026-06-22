package com.orion.player.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure key-value store for device credentials using EncryptedSharedPreferences.
 * Stores hardwareId, deviceToken, organizationId, and pairing state.
 */
@Singleton
class SecurePrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "orion_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_HARDWARE_ID = "hardware_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_ORGANIZATION_ID = "organization_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_IS_PAIRED = "is_paired"
        private const val KEY_PAIRING_SECRET = "pairing_secret"
        private const val KEY_PAIRING_CODE = "pairing_code"
    }

    /**
     * Returns the persisted hardwareId, or generates and persists a new UUID on first call.
     */
    fun getOrCreateHardwareId(): String {
        val existing = prefs.getString(KEY_HARDWARE_ID, null)?.trim()
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_HARDWARE_ID, newId).apply()
        return newId
    }

    var pairingSecret: String?
        get() = prefs.getString(KEY_PAIRING_SECRET, null)
        set(value) = prefs.edit().putString(KEY_PAIRING_SECRET, value).apply()

    var pairingCode: String?
        get() = prefs.getString(KEY_PAIRING_CODE, null)
        set(value) = prefs.edit().putString(KEY_PAIRING_CODE, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var organizationId: String?
        get() = prefs.getString(KEY_ORGANIZATION_ID, null)
        set(value) = prefs.edit().putString(KEY_ORGANIZATION_ID, value).apply()

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var isPaired: Boolean
        get() = prefs.getBoolean(KEY_IS_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PAIRED, value).apply()

    /**
     * Saves all pairing credentials at once.
     */
    fun savePairingCredentials(
        deviceToken: String,
        organizationId: String,
        deviceName: String?
    ) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_ORGANIZATION_ID, organizationId)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putBoolean(KEY_IS_PAIRED, true)
            .apply()
    }

    /**
     * Clears all stored credentials (e.g., on 401 / unpairing).
     */
    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_ORGANIZATION_ID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_PAIRING_SECRET)
            .remove(KEY_PAIRING_CODE)
            .putBoolean(KEY_IS_PAIRED, false)
            .apply()
    }

    /**
     * Returns the Bearer token string for API calls, or null if not paired.
     */
    fun getBearerToken(): String? {
        return deviceToken?.let { "Bearer $it" }
    }
}
