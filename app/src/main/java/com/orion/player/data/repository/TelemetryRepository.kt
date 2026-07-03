package com.orion.player.data.repository

import android.util.Log
import com.orion.player.BuildConfig
import com.orion.player.data.analytics.PopLogRecord
import com.orion.player.data.analytics.PopTelemetryLogger
import com.orion.player.data.enterprise.CrashLogStore
import com.orion.player.data.enterprise.DeviceLogsUploadRequest
import com.orion.player.data.enterprise.DeviceLogCollector
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
    private val sessionGuard: SessionGuard,
    private val deviceLogCollector: DeviceLogCollector,
    private val crashLogStore: CrashLogStore
) {
    companion object {
        private const val TAG = "OrionTelemetry"
        private const val FLUSH_BATCH_SIZE = 50
    }

    suspend fun sendHeartbeat(body: HeartbeatRequest): HeartbeatResponse? {
        if (!sessionGuard.isPairedWithToken()) return null
        val token = sessionGuard.requirePairedToken()
        return try {
            val response = api.sendHeartbeat(token = token, body = body)
            flushQueuedHeartbeats()
            uploadPendingCrashLog()
            response
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed, queuing locally: ${e.message}")
            queueHeartbeat(body)
            null
        }
    }

    private suspend fun queueHeartbeat(body: HeartbeatRequest) {
        heartbeatQueueDao.insert(
            QueuedHeartbeatEntity(
                cpu = body.cpu,
                ram = body.ram,
                temp = body.temp,
                currentContent = body.currentContent,
                payloadJson = HeartbeatPayloadCodec.encode(body),
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
                    val body = heartbeat.payloadJson
                        ?.let(HeartbeatPayloadCodec::decode)
                        ?: HeartbeatRequest(
                            cpu = heartbeat.cpu,
                            ram = heartbeat.ram,
                            temp = heartbeat.temp,
                            currentContent = heartbeat.currentContent
                        )
                    api.sendHeartbeat(token = token, body = body)
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
        PopTelemetryLogger.logGenerated(record.assetName, record.status)
        val pending = popLogDao.getUnsyncedCount()
        PopTelemetryLogger.logQueueStatus(pending)
        val synced = flushPopLogs()
        Log.d(TAG, "Queued PoP for ${record.assetName} (${record.status}), synced=$synced pending=${popLogDao.getUnsyncedCount()}")
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

                val received = response.received.coerceAtLeast(0)
                if (received <= 0) {
                    PopTelemetryLogger.logUploadFailed(
                        attempted = unsynced.size,
                        reason = "CMS received=0"
                    )
                    Log.w(TAG, "PoP flush rejected by CMS: received=0, keeping ${unsynced.size} queued")
                    break
                }

                val confirmed = unsynced.take(received.coerceAtMost(unsynced.size))
                val ids = confirmed.map { it.id }
                popLogDao.markSynced(ids)
                popLogDao.deleteSynced()
                totalSynced += confirmed.size
                PopTelemetryLogger.logUploaded(confirmed.size)
                Log.d(TAG, "Flushed ${confirmed.size} PoP log(s), CMS received=$received")

                if (received < unsynced.size) {
                    Log.w(
                        TAG,
                        "PoP partial accept: sent=${unsynced.size} received=$received, remainder stays queued"
                    )
                    break
                }
            } catch (e: HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                PopTelemetryLogger.logUploadFailed(unsynced.size, "HTTP ${e.code()}")
                Log.e(TAG, "PoP flush failed: HTTP ${e.code()} $errorBody", e)
                break
            } catch (e: Exception) {
                PopTelemetryLogger.logUploadFailed(unsynced.size, e.message ?: "unknown")
                Log.e(TAG, "PoP flush failed: ${e.message}", e)
                break
            }
        }

        PopTelemetryLogger.logQueueStatus(popLogDao.getUnsyncedCount())
        return totalSynced
    }

    suspend fun flushAll() {
        flushQueuedHeartbeats()
        flushPopLogs()
        uploadPendingCrashLog()
    }

    suspend fun uploadDeviceLogs(screenshotPath: String? = null): Boolean {
        if (!sessionGuard.isPairedWithToken()) return false
        val token = sessionGuard.requirePairedToken()
        return try {
            val logs = buildString {
                append(deviceLogCollector.readLogs())
                if (!screenshotPath.isNullOrBlank()) {
                    appendLine()
                    append("screenshot=$screenshotPath")
                }
            }
            api.uploadDeviceLogs(
                token = token,
                body = DeviceLogsUploadRequest(
                    logs = logs,
                    crashLog = crashLogStore.readPendingCrash(),
                    deviceName = securePrefs.deviceName,
                    appVersion = BuildConfig.VERSION_NAME,
                    uploadedAt = java.time.Instant.now().toString()
                )
            )
            crashLogStore.clearPendingCrash()
            deviceLogCollector.log("UPLOAD", "Device logs uploaded (${logs.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Device log upload failed: ${e.message}", e)
            deviceLogCollector.logError("Log upload failed: ${e.message}")
            false
        }
    }

    private suspend fun uploadPendingCrashLog() {
        if (crashLogStore.readPendingCrash() == null) return
        uploadDeviceLogs()
    }

    suspend fun getUnsyncedPopCount(): Int = popLogDao.getUnsyncedCount()

    private fun com.orion.player.data.local.PopLogEntity.toApiEntry(): PopLogEntry {
        val start = startTime
        return PopLogEntry(
            assetName = assetName,
            content = assetName,
            playlistName = playlistName,
            startTime = start,
            timestamp = start,
            endTime = endTime,
            durationSeconds = durationSeconds,
            status = status
        )
    }
}
