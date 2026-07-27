package com.orion.player.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.orion.player.data.recovery.PlayerRuntimeConfig
import com.orion.player.data.sync.SyncConfig
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
        private const val KEY_CMS_DEVICE_ID = "cms_device_id"
        private const val KEY_ORGANIZATION_ID = "organization_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_IS_PAIRED = "is_paired"
        private const val KEY_PAIRING_SECRET = "pairing_secret"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_KIOSK_MODE = "kiosk_mode_enabled"
        private const val KEY_LAST_SUCCESSFUL_SYNC_AT = "last_successful_sync_at"
        private const val KEY_SYNC_INTERVAL_SECONDS = "sync_interval_seconds"
        private const val KEY_REVISION_POLL_INTERVAL_SECONDS = "revision_poll_interval_seconds"
        private const val KEY_LAST_STORED_REVISION = "last_stored_revision"
        private const val KEY_LAST_STORED_PLAYLIST_ID = "last_stored_playlist_id"
        private const val KEY_LAST_STORED_LAYOUT_ID = "last_stored_layout_id"
        private const val KEY_PAIRED_AT_MS = "paired_at_ms"
        private const val KEY_INITIAL_DOWNLOAD_STARTED = "initial_download_started"
        private const val KEY_INITIAL_SYNC_TIMEOUT_SECONDS = "initial_sync_timeout_seconds"
        private const val KEY_PAIRING_BOOTSTRAP_PENDING = "pairing_bootstrap_pending"
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
        get() {
            val hardwareId = prefs.getString(KEY_HARDWARE_ID, null)?.trim().orEmpty()
            if (hardwareId.isBlank()) return null
            val scoped = prefs.getString(deviceTokenKey(hardwareId), null)
            if (!scoped.isNullOrBlank()) return scoped
            val legacy = prefs.getString(KEY_DEVICE_TOKEN, null)
            if (!legacy.isNullOrBlank()) {
                prefs.edit()
                    .putString(deviceTokenKey(hardwareId), legacy)
                    .remove(KEY_DEVICE_TOKEN)
                    .apply()
                return legacy
            }
            return null
        }
        set(value) {
            val hardwareId = getOrCreateHardwareId()
            val editor = prefs.edit()
            if (value.isNullOrBlank()) {
                editor.remove(deviceTokenKey(hardwareId)).remove(KEY_DEVICE_TOKEN)
            } else {
                editor.putString(deviceTokenKey(hardwareId), value)
            }
            editor.apply()
        }

    /** CMS-assigned device record ID, confirmed from pop-logs responses. */
    var cmsDeviceId: String?
        get() = prefs.getString(KEY_CMS_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_CMS_DEVICE_ID, value).apply()

    var organizationId: String?
        get() = prefs.getString(KEY_ORGANIZATION_ID, null)
        set(value) = prefs.edit().putString(KEY_ORGANIZATION_ID, value).apply()

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var isPaired: Boolean
        get() = prefs.getBoolean(KEY_IS_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PAIRED, value).apply()

    /** When true, the player pins itself and returns to foreground on accidental exit. */
    var kioskModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_MODE, PlayerRuntimeConfig.KIOSK_MODE_ENABLED_DEFAULT)
        set(value) = prefs.edit().putBoolean(KEY_KIOSK_MODE, value).apply()

    var lastSuccessfulSyncAt: String?
        get() = prefs.getString(KEY_LAST_SUCCESSFUL_SYNC_AT, null)
        set(value) = prefs.edit().putString(KEY_LAST_SUCCESSFUL_SYNC_AT, value).apply()

    /** Seconds between full /player/sync polls (server-configurable, default 120). */
    var syncIntervalSeconds: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL_SECONDS, SyncConfig.DEFAULT_SYNC_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL_SECONDS, value).apply()

    /** Seconds between lightweight /player/sync-revision polls (server-configurable, default 5). */
    var revisionPollIntervalSeconds: Int
        get() = prefs.getInt(KEY_REVISION_POLL_INTERVAL_SECONDS, SyncConfig.DEFAULT_REVISION_POLL_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_REVISION_POLL_INTERVAL_SECONDS, value).apply()

    var lastStoredRevision: String?
        get() = prefs.getString(KEY_LAST_STORED_REVISION, null)
        set(value) = prefs.edit().putString(KEY_LAST_STORED_REVISION, value).apply()

    var lastStoredPlaylistId: String?
        get() = prefs.getString(KEY_LAST_STORED_PLAYLIST_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_STORED_PLAYLIST_ID, value).apply()

    var lastStoredLayoutId: String?
        get() = prefs.getString(KEY_LAST_STORED_LAYOUT_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_STORED_LAYOUT_ID, value).apply()

    var pairedAtMs: Long
        get() = prefs.getLong(KEY_PAIRED_AT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_PAIRED_AT_MS, value).apply()

    var initialDownloadStarted: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_DOWNLOAD_STARTED, false)
        set(value) = prefs.edit().putBoolean(KEY_INITIAL_DOWNLOAD_STARTED, value).apply()

    var initialSyncTimeoutSeconds: Int
        get() = prefs.getInt(KEY_INITIAL_SYNC_TIMEOUT_SECONDS, SyncConfig.DEFAULT_SYNC_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_INITIAL_SYNC_TIMEOUT_SECONDS, value).apply()

    var pairingBootstrapPending: Boolean
        get() = prefs.getBoolean(KEY_PAIRING_BOOTSTRAP_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRING_BOOTSTRAP_PENDING, value).apply()

    fun isAuthenticated(): Boolean =
        isPaired && !deviceToken.isNullOrBlank()

    /**
     * Saves all pairing credentials at once.
     */
    fun savePairingCredentials(
        deviceToken: String,
        organizationId: String,
        deviceName: String?
    ) {
        val hardwareId = getOrCreateHardwareId()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(deviceTokenKey(hardwareId), deviceToken)
            .putString(KEY_ORGANIZATION_ID, organizationId)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putBoolean(KEY_IS_PAIRED, true)
            .putLong(KEY_PAIRED_AT_MS, now)
            .putBoolean(KEY_INITIAL_DOWNLOAD_STARTED, false)
            .putBoolean(KEY_PAIRING_BOOTSTRAP_PENDING, true)
            .remove(KEY_CMS_DEVICE_ID)
            .apply()
    }

    /**
     * Clears all stored credentials (e.g., on 401 / unpairing).
     */
    fun clearCredentials() {
        val hardwareId = prefs.getString(KEY_HARDWARE_ID, null)?.trim().orEmpty()
        val editor = prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_ORGANIZATION_ID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_PAIRING_SECRET)
            .remove(KEY_PAIRING_CODE)
            .remove(KEY_CMS_DEVICE_ID)
            .remove(KEY_PAIRED_AT_MS)
            .remove(KEY_INITIAL_DOWNLOAD_STARTED)
            .remove(KEY_LAST_STORED_REVISION)
            .remove(KEY_LAST_STORED_PLAYLIST_ID)
            .remove(KEY_LAST_STORED_LAYOUT_ID)
            .putBoolean(KEY_IS_PAIRED, false)
        if (hardwareId.isNotBlank()) {
            editor.remove(deviceTokenKey(hardwareId))
        }
        editor.apply()
    }

    /**
     * Returns the Bearer token string for API calls, or null if not paired.
     */
    fun getBearerToken(): String? {
        return deviceToken?.let { "Bearer $it" }
    }

    fun deviceTokenPrefix(): String =
        deviceToken?.take(8)?.let { "$it..." } ?: "none"

    private fun deviceTokenKey(hardwareId: String): String =
        "device_token_$hardwareId"
}
