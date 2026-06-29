package com.orion.player.data.repository

import android.util.Log
import com.orion.player.data.analytics.PopLogRecord
import com.orion.player.data.local.HeartbeatQueueDao
import com.orion.player.data.local.PopLogDao
import com.orion.player.data.local.QueuedHeartbeatEntity
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.HeartbeatRequest
import com.orion.player.data.remote.HeartbeatResponse
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.PopLogEntry
import com.orion.player.data.remote.PopLogsRequest
import com.orion.player.util.SessionGuard
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for device health telemetry and Proof-of-Play analytics.
 * Queues data locally when offline and flushes after reconnect.
 */
@Singleton
class TelemetryRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val popLogDao: PopLogDao,
    private val heartbeatQueueDao: HeartbeatQueueDao,
    private val securePrefs: SecurePrefs,
    private val sessionGuard: SessionGuard
) {
    companion object {
        private const val TAG = "OrionTelemetry"
        private const val FLUSH_BATCH_SIZE = 50
    }

    suspend fun sendHeartbeat(
        cpu: Int,
        ram: Int,
        temp: Int,
        currentContent: String? = null
    ): HeartbeatResponse? {
        if (!sessionGuard.isPairedWithToken()) return null
        val token = sessionGuard.requirePairedToken()
        return try {
            val response = api.sendHeartbeat(
                token = token,
                body = HeartbeatRequest(
                    cpu = cpu,
                    ram = ram,
                    temp = temp,
                    currentContent = currentContent
                )
            )
            flushQueuedHeartbeats()
            response
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed, queuing locally: ${e.message}")
            queueHeartbeat(cpu, ram, temp, currentContent)
            null
        }
    }

    private suspend fun queueHeartbeat(
        cpu: Int,
        ram: Int,
        temp: Int,
        currentContent: String?
    ) {
        heartbeatQueueDao.insert(
            QueuedHeartbeatEntity(
                cpu = cpu,
                ram = ram,
                temp = temp,
                currentContent = currentContent,
                recordedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun flushQueuedHeartbeats(): Int {
        if (!sessionGuard.isPairedWithToken()) return 0
        val token = sessionGuard.requirePairedToken()
        var flushed = 0

        while (true) {
            val batch = heartbeatQueueDao.getUnsynced(limit = 10)
            if (batch.isEmpty()) break

            try {
                for (heartbeat in batch) {
                    api.sendHeartbeat(
                        token = token,
                        body = HeartbeatRequest(
                            cpu = heartbeat.cpu,
                            ram = heartbeat.ram,
                            temp = heartbeat.temp,
                            currentContent = heartbeat.currentContent
                        )
                    )
                }
                val ids = batch.map { it.id }
                heartbeatQueueDao.markSynced(ids)
                heartbeatQueueDao.deleteSynced()
                flushed += batch.size
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat flush failed: ${e.message}")
                break
            }
        }

        return flushed
    }

    suspend fun queuePopLog(record: PopLogRecord) {
        popLogDao.insert(record.toEntity())
        val synced = flushPopLogs()
        Log.d(TAG, "Queued PoP for ${record.assetName} (${record.status}), synced=$synced")
    }

    suspend fun flushPopLogs(): Int {
        if (!sessionGuard.isPairedWithToken()) return 0
        val token = sessionGuard.requirePairedToken()
        var totalSynced = 0

        while (true) {
            val unsynced = popLogDao.getUnsynced(FLUSH_BATCH_SIZE)
            if (unsynced.isEmpty()) break

            try {
                val logEntries = unsynced.map { it.toApiEntry() }
                val response = api.submitPopLogs(
                    token = token,
                    body = PopLogsRequest(logs = logEntries)
                )

                val ids = unsynced.map { it.id }
                popLogDao.markSynced(ids)
                popLogDao.deleteSynced()
                totalSynced += response.received
                Log.d(TAG, "Flushed ${response.received} PoP log(s)")
            } catch (e: HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Log.e(TAG, "PoP flush failed: HTTP ${e.code()} $errorBody", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "PoP flush failed: ${e.message}", e)
                break
            }
        }

        return totalSynced
    }

    suspend fun flushAll() {
        flushQueuedHeartbeats()
        flushPopLogs()
    }

    suspend fun getUnsyncedPopCount(): Int = popLogDao.getUnsyncedCount()

    private fun com.orion.player.data.local.PopLogEntity.toApiEntry() =
        PopLogEntry(
            assetName = assetName,
            playlistName = playlistName,
            startTime = startTime,
            endTime = endTime,
            durationSeconds = durationSeconds,
            status = status
        )
}
