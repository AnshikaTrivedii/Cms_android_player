package com.orion.player.data.analytics

import android.util.Log
import com.orion.player.data.repository.TelemetryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped PoP session sink. Survives Activity/ViewModel teardown so
 * finalized sessions can still be queued while the foreground service is alive.
 */
@Singleton
class PopSessionRecorder @Inject constructor(
    private val telemetryRepository: TelemetryRepository
) {
    suspend fun record(record: PopLogRecord) {
        try {
            telemetryRepository.queuePopLog(record)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue PoP for ${record.assetName}: ${e.message}", e)
            PopTelemetryLogger.logSkipped("queue_failed:${e.message}")
            throw e
        }
    }

    companion object {
        private const val TAG = "OrionPoP"
    }
}
