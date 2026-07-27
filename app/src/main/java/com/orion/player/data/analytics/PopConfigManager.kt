package com.orion.player.data.analytics

import com.orion.player.data.remote.PlayerFeatures
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the CMS expects Proof-of-Play logs from this device.
 * Updated from heartbeat and sync responses.
 */
@Singleton
class PopConfigManager @Inject constructor() {
    private val popEnabled = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)

    fun update(popLogsExpected: Boolean?, features: PlayerFeatures?) {
        if (popLogsExpected == null && features?.proofOfPlay == null) return
        val enabled = popLogsExpected == true || features?.proofOfPlay == true
        popEnabled.set(enabled)
        configured.set(true)
        PopTelemetryLogger.logConfigUpdate(
            enabled = enabled,
            popLogsExpected = popLogsExpected,
            proofOfPlay = features?.proofOfPlay
        )
    }

    fun isPopEnabled(): Boolean = configured.get() && popEnabled.get()

    fun disablePermanently(reason: String) {
        popEnabled.set(false)
        configured.set(true)
        PopTelemetryLogger.logDisabled(reason)
    }
}
