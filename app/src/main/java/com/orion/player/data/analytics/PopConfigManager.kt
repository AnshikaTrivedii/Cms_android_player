package com.orion.player.data.analytics

import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.PlayerFeatures
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Separates PoP **collection** (always queue while paired) from **upload**
 * (paused when CMS says it is not expecting logs).
 *
 * Upload preference is persisted so a process restart does not silently stop
 * reporting until the next heartbeat.
 */
@Singleton
class PopConfigManager @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    private val configured = AtomicBoolean(securePrefs.popUploadConfigured)
    private val uploadEnabled = AtomicBoolean(
        if (securePrefs.popUploadConfigured) securePrefs.popUploadEnabled else true
    )

    fun update(popLogsExpected: Boolean?, features: PlayerFeatures?) {
        if (popLogsExpected == null && features?.proofOfPlay == null) return
        val enabled = popLogsExpected == true || features?.proofOfPlay == true
        setUploadEnabled(enabled, source = "server_config")
        PopTelemetryLogger.logConfigUpdate(
            enabled = enabled,
            popLogsExpected = popLogsExpected,
            proofOfPlay = features?.proofOfPlay
        )
    }

    /**
     * Collection stays on whenever the device is paired — never gated by CMS.
     * Callers still check pairing separately.
     */
    fun isCollectEnabled(): Boolean = true

    /** Upload may be paused by CMS without dropping the local queue. */
    fun isUploadEnabled(): Boolean = configured.get() && uploadEnabled.get()

    /** @deprecated Use [isUploadEnabled] / [isCollectEnabled]. */
    fun isPopEnabled(): Boolean = isUploadEnabled()

    /**
     * Pause uploads only. Never wipe the local queue.
     */
    fun pauseUpload(reason: String) {
        setUploadEnabled(false, source = reason)
        PopTelemetryLogger.logDisabled(reason)
    }

    @Deprecated("Use pauseUpload — never permanently destroy the queue")
    fun disablePermanently(reason: String) {
        pauseUpload(reason)
    }

    fun markPairedDefaultUploadEnabled() {
        if (!securePrefs.popUploadConfigured) {
            setUploadEnabled(true, source = "pairing_default")
        }
    }

    private fun setUploadEnabled(enabled: Boolean, source: String) {
        val previous = uploadEnabled.get()
        uploadEnabled.set(enabled)
        configured.set(true)
        securePrefs.popUploadConfigured = true
        securePrefs.popUploadEnabled = enabled
        if (previous != enabled) {
            PopTelemetryLogger.logConfigUpdate(
                enabled = enabled,
                popLogsExpected = enabled,
                proofOfPlay = enabled
            )
            android.util.Log.i(
                "OrionPoP",
                "Upload gate changed enabled=$enabled source=$source"
            )
        }
    }
}
