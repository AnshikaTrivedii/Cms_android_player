package com.orion.player.data.repository

import com.orion.player.data.local.PopLogDao
import com.orion.player.data.local.PopLogEntity
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.HeartbeatRequest
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.PopLogEntry
import com.orion.player.data.remote.PopLogsRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for device health telemetry and Proof-of-Play analytics.
 * Handles heartbeat sending, PoP log queuing, and batch flushing.
 */
@Singleton
class TelemetryRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val popLogDao: PopLogDao,
    private val securePrefs: SecurePrefs
) {
    /**
     * Sends a heartbeat to the backend with device health metrics.
     * Returns true on success, false on failure.
     */
    suspend fun sendHeartbeat(
        cpu: Int,
        ram: Int,
        temp: Int,
        currentContent: String? = null
    ): Boolean {
        val token = securePrefs.getBearerToken() ?: return false
        return try {
            api.sendHeartbeat(
                token = token,
                body = HeartbeatRequest(
                    cpu = cpu,
                    ram = ram,
                    temp = temp,
                    currentContent = currentContent
                )
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Queues a Proof-of-Play log entry in the local Room database.
     */
    suspend fun queuePopLog(
        content: String,
        status: String,
        timestamp: String
    ) {
        popLogDao.insert(
            PopLogEntity(
                content = content,
                status = status,
                timestamp = timestamp
            )
        )
    }

    /**
     * Flushes unsynced PoP logs to the backend.
     * On success, marks them as synced and cleans up.
     * Returns the number of logs successfully synced.
     */
    suspend fun flushPopLogs(): Int {
        val token = securePrefs.getBearerToken() ?: return 0
        val unsynced = popLogDao.getUnsynced(50)

        if (unsynced.isEmpty()) return 0

        return try {
            val logEntries = unsynced.map { entity ->
                PopLogEntry(
                    content = entity.content,
                    status = entity.status,
                    timestamp = entity.timestamp
                )
            }

            val response = api.submitPopLogs(
                token = token,
                body = PopLogsRequest(logs = logEntries)
            )

            // Mark as synced and clean up
            val ids = unsynced.map { it.id }
            popLogDao.markSynced(ids)
            popLogDao.deleteSynced()

            response.received
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    /**
     * Returns the count of unsynced PoP logs.
     */
    suspend fun getUnsyncedCount(): Int = popLogDao.getUnsyncedCount()
}
