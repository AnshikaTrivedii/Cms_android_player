package com.orion.player.data.repository

import android.util.Log
import com.google.gson.Gson
import com.orion.player.BuildConfig
import com.orion.player.data.analytics.PopConfigManager
import com.orion.player.data.analytics.PopHealthTracker
import com.orion.player.data.analytics.PopLogRecord
import com.orion.player.data.analytics.PopTelemetryLogger
import com.orion.player.data.enterprise.CrashLogStore
import com.orion.player.data.enterprise.DeviceLogsUploadRequest
import com.orion.player.data.enterprise.DeviceLogCollector
import com.orion.player.data.enterprise.RemoteCommand
import com.orion.player.data.local.HeartbeatQueueDao
import com.orion.player.data.local.PopLogDao
import com.orion.player.data.local.QueuedHeartbeatEntity
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.GsonConfig
import com.orion.player.data.remote.HeartbeatRequest
import com.orion.player.data.remote.HeartbeatResponse
import com.orion.player.data.remote.PendingRemoteCommand
import com.orion.player.data.remote.DeviceReportRequest
import com.orion.player.data.remote.DeviceReportResponse
import com.orion.player.data.remote.SystemLogEntry
import com.orion.player.data.remote.SystemLogsRequest
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.PlayerFeatures
import com.orion.player.data.remote.PopLogEntry
import com.orion.player.data.remote.PopLogsRequest
import com.orion.player.data.config.DeviceConfigManager
import com.orion.player.data.registration.DeviceRegistrationManager
import com.orion.player.data.registration.DeviceRegistrationStatusParser
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.data.sync.SyncIntervalConfig
import com.orion.player.data.telemetry.HeartbeatPayloadBuilder
import com.orion.player.data.telemetry.HeartbeatPayloadNormalizer
import com.orion.player.data.telemetry.HeartbeatTelemetryLogger
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
    private val crashLogStore: CrashLogStore,
    private val popConfigManager: PopConfigManager,
    private val popHealthTracker: PopHealthTracker,
    private val syncIntervalConfig: SyncIntervalConfig,
    private val heartbeatPayloadBuilder: HeartbeatPayloadBuilder,
    private val deviceRegistrationManager: DeviceRegistrationManager,
    private val deviceConfigManager: DeviceConfigManager
) {
    companion object {
        private const val TAG = "OrionTelemetry"
        private const val FLUSH_BATCH_SIZE = 50
        private const val MAX_QUEUE_SIZE = 10_000
        private const val MAX_HEARTBEAT_ATTEMPTS = 3
        private const val POP_DISABLED_REASON = "proof_of_play_disabled"
        private val gson: Gson = GsonConfig.create()
    }

    fun applyServerPopConfig(popLogsExpected: Boolean?, features: PlayerFeatures?) {
        popConfigManager.update(popLogsExpected, features)
    }

    suspend fun sendHeartbeat(body: HeartbeatRequest): HeartbeatResponse? {
        if (!sessionGuard.isPairedWithToken()) return null
        val token = sessionGuard.requirePairedToken()
        val deviceId = securePrefs.getOrCreateHardwareId()
        val normalized = HeartbeatPayloadNormalizer.normalize(body)
        val url = HeartbeatPayloadNormalizer.endpointUrl()

        var lastError: String? = null
        var lastCode: Int? = null
        var lastBody: String? = null

        attemptLoop@ for (attempt in 0 until MAX_HEARTBEAT_ATTEMPTS) {
            val attemptNumber = attempt + 1
            val started = System.currentTimeMillis()
            HeartbeatTelemetryLogger.logAttempt(deviceId, attemptNumber, url, normalized)
            try {
                val response = api.sendHeartbeat(token = token, body = normalized)
                val mapped = mapHeartbeatResponse(response)
                deviceRegistrationManager.handleStatus(
                    DeviceRegistrationStatusParser.fromSuccessField(mapped.deviceStatus)
                )
                applyServerPopConfig(mapped.popLogsExpected, mapped.features)
                deviceConfigManager.applyFromServer(
                    configVersion = mapped.configVersion,
                    stretchToFit = mapped.stretchToFit,
                    orientation = mapped.orientation,
                    display = mapped.display
                )
                syncIntervalConfig.updateInterval(mapped.syncIntervalSeconds)
                mapped.initialSyncTimeoutSeconds?.let {
                    securePrefs.initialSyncTimeoutSeconds = it.coerceIn(
                        SyncIntervalConfig.MIN_SECONDS,
                        SyncIntervalConfig.MAX_SECONDS
                    )
                }
                val elapsed = System.currentTimeMillis() - started
                HeartbeatTelemetryLogger.logSuccess(
                    deviceId = deviceId,
                    attempt = attemptNumber,
                    responseCode = 200,
                    responseBody = gson.toJson(mapped),
                    elapsedMs = elapsed
                )
                runCatching { flushQueuedHeartbeats() }
                    .onFailure { Log.w(TAG, "Heartbeat flush queue failed: ${it.message}") }
                runCatching { uploadPendingCrashLog() }
                    .onFailure { Log.w(TAG, "Crash log upload failed: ${it.message}") }
                return mapped
            } catch (e: HttpException) {
                lastCode = e.code()
                lastBody = e.response()?.errorBody()?.string()
                lastError = "HTTP ${e.code()}: ${lastBody ?: e.message()}"
                HeartbeatTelemetryLogger.logFailure(
                    deviceId = deviceId,
                    attempt = attemptNumber,
                    responseCode = e.code(),
                    responseBody = lastBody,
                    error = lastError,
                    elapsedMs = System.currentTimeMillis() - started
                )
                if (e.code() == 401) {
                    deviceRegistrationManager.handleStatus(
                        DeviceRegistrationStatusParser.fromUnauthorized(lastBody, e.message())
                    )
                    return null
                }
                if (e.code() in 400..499 && e.code() != 408 && e.code() != 429) {
                    break@attemptLoop
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                HeartbeatTelemetryLogger.logFailure(
                    deviceId = deviceId,
                    attempt = attemptNumber,
                    responseCode = lastCode,
                    responseBody = lastBody,
                    error = lastError,
                    elapsedMs = System.currentTimeMillis() - started
                )
            }
        }

        Log.w(TAG, "Heartbeat failed after $MAX_HEARTBEAT_ATTEMPTS attempts: $lastError")
        queueHeartbeat(normalized)
        return null
    }

    private fun mapHeartbeatResponse(response: HeartbeatResponse): HeartbeatResponse {
        if (!response.commands.isNullOrEmpty()) return response
        val pending = response.pendingCommand ?: run {
            val cmd = response.command
            if (cmd.isNullOrBlank()) return response
            PendingRemoteCommand(id = response.commandId, command = cmd, params = null)
        }
        val command = RemoteCommand(
            id = pending.id,
            type = pending.command,
            params = pending.params
        )
        return response.copy(commands = listOf(command))
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
                        ?.let(HeartbeatPayloadNormalizer::normalize)
                        ?: HeartbeatPayloadNormalizer.normalize(
                            HeartbeatRequest(
                                cpu = heartbeat.cpu,
                                ram = heartbeat.ram,
                                temp = heartbeat.temp,
                                currentContent = heartbeat.currentContent
                            )
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
        if (!sessionGuard.isPairedWithToken()) {
            PopTelemetryLogger.logSkipped("not_paired")
            return
        }
        if (!popConfigManager.isCollectEnabled()) {
            PopTelemetryLogger.logSkipped("collect_disabled")
            return
        }

        enforceQueueCap()
        popLogDao.insert(record.toEntity())
        PopTelemetryLogger.logGenerated(record.assetName, record.status)
        popHealthTracker.recordGenerated()
        val pending = popLogDao.getUnsyncedCount()
        PopTelemetryLogger.logQueueStatus(pending)

        if (!popConfigManager.isUploadEnabled()) {
            PopTelemetryLogger.logSkipped("upload_paused")
            Log.d(TAG, "Queued PoP for ${record.assetName} (upload paused) pending=$pending")
            return
        }

        val synced = flushPopLogs()
        Log.d(
            TAG,
            "Queued PoP for ${record.assetName} (${record.status}), synced=$synced pending=${popLogDao.getUnsyncedCount()}"
        )
    }

    private suspend fun enforceQueueCap() {
        val pending = popLogDao.getUnsyncedCount()
        if (pending < MAX_QUEUE_SIZE) return
        val overflow = pending - MAX_QUEUE_SIZE + 1
        popLogDao.deleteOldestUnsynced(overflow)
        val msg = "queue_cap_drop overflow=$overflow pendingWas=$pending"
        popHealthTracker.recordError(msg)
        PopTelemetryLogger.logSkipped(msg)
        Log.w(TAG, "PoP queue capped — dropped $overflow oldest unsynced row(s)")
    }

    suspend fun flushPopLogs(): Int {
        if (!sessionGuard.isPairedWithToken()) return 0
        if (!popConfigManager.isUploadEnabled()) {
            PopTelemetryLogger.logSkipped("upload_paused")
            return 0
        }

        val token = sessionGuard.requirePairedToken()
        val hardwareId = securePrefs.getOrCreateHardwareId()
        val tokenPrefix = securePrefs.deviceTokenPrefix()
        var totalSynced = 0

        while (true) {
            val unsynced = popLogDao.getUnsynced(FLUSH_BATCH_SIZE)
            if (unsynced.isEmpty()) break

            PopTelemetryLogger.logSubmitAttempt(
                hardwareId = hardwareId,
                tokenPrefix = tokenPrefix,
                pendingCount = popLogDao.getUnsyncedCount(),
                batchSize = unsynced.size
            )

            try {
                val logEntries = unsynced.map { it.toApiEntry() }
                val response = api.submitPopLogs(
                    token = token,
                    body = PopLogsRequest(logs = logEntries)
                )

                response.popLogsExpected?.let { expected ->
                    popConfigManager.update(expected, PlayerFeatures(proofOfPlay = expected))
                }

                if (response.accepted == false && response.reason == POP_DISABLED_REASON) {
                    // Pause upload only — never wipe evidence.
                    popConfigManager.pauseUpload(POP_DISABLED_REASON)
                    popHealthTracker.recordError(POP_DISABLED_REASON)
                    PopTelemetryLogger.logQueueStatus(popLogDao.getUnsyncedCount())
                    Log.w(
                        TAG,
                        "PoP upload paused by CMS ($POP_DISABLED_REASON) — retaining ${unsynced.size} queued log(s)"
                    )
                    break
                }

                if (response.accepted == false) {
                    val reason = response.reason ?: "CMS accepted=false"
                    popHealthTracker.recordError(reason)
                    PopTelemetryLogger.logUploadFailed(unsynced.size, reason)
                    Log.w(TAG, "PoP flush rejected: reason=$reason")
                    break
                }

                val responseDeviceId = response.deviceId?.trim().orEmpty()
                if (responseDeviceId.isNotBlank()) {
                    val storedId = securePrefs.cmsDeviceId
                    if (storedId.isNullOrBlank()) {
                        securePrefs.cmsDeviceId = responseDeviceId
                    } else if (storedId != responseDeviceId) {
                        // Authoritative CMS id wins — do not deadlock forever.
                        PopTelemetryLogger.logDeviceIdMismatch(storedId, responseDeviceId)
                        Log.e(
                            TAG,
                            "PoP deviceId mismatch stored=$storedId response=$responseDeviceId — adopting CMS id"
                        )
                        securePrefs.cmsDeviceId = responseDeviceId
                        popHealthTracker.recordError("device_id_mismatch_adopted:$responseDeviceId")
                        runCatching {
                            submitSystemLogs(
                                category = "pop",
                                message = "Adopted CMS deviceId after mismatch",
                                metadata = mapOf(
                                    "storedId" to storedId,
                                    "responseDeviceId" to responseDeviceId,
                                    "tokenPrefix" to tokenPrefix
                                )
                            )
                        }
                    }
                }

                val received = response.received.coerceAtLeast(0)
                if (received <= 0) {
                    val reason = response.reason ?: "CMS received=0"
                    popHealthTracker.recordError(reason)
                    PopTelemetryLogger.logUploadFailed(
                        attempted = unsynced.size,
                        reason = reason
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
                popHealthTracker.recordUploaded(confirmed.size)
                PopTelemetryLogger.logSubmitSuccess(
                    deviceId = response.deviceId,
                    deviceName = response.deviceName,
                    received = received,
                    skipped = response.skipped
                )
                Log.d(
                    TAG,
                    "Flushed ${confirmed.size} PoP log(s) deviceId=${response.deviceId} CMS received=$received"
                )

                if (received < unsynced.size) {
                    Log.w(
                        TAG,
                        "PoP partial accept: sent=${unsynced.size} received=$received, remainder stays queued"
                    )
                    break
                }
            } catch (e: HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                popHealthTracker.recordError("HTTP ${e.code()}")
                PopTelemetryLogger.logUploadFailed(unsynced.size, "HTTP ${e.code()}")
                Log.e(TAG, "PoP flush failed: HTTP ${e.code()} body=$errorBody", e)
                break
            } catch (e: Exception) {
                popHealthTracker.recordError(e.message ?: "unknown")
                PopTelemetryLogger.logUploadFailed(unsynced.size, e.message ?: "unknown")
                Log.e(TAG, "PoP flush failed: ${e.message}", e)
                break
            }
        }

        PopTelemetryLogger.logQueueStatus(popLogDao.getUnsyncedCount())
        return totalSynced
    }

    suspend fun popHealthSnapshot() =
        popHealthTracker.snapshot(pendingCount = popLogDao.getUnsyncedCount())


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

    suspend fun submitDeviceReport(
        completedCommandId: String? = null,
        commandFailed: Boolean = false,
        commandError: String? = null
    ): DeviceReportResponse? {
        if (!sessionGuard.isPairedWithToken()) return null
        val token = sessionGuard.requirePairedToken()
        return try {
            val heartbeat = heartbeatPayloadBuilder.build()
            val body = DeviceReportRequest(
                cpu = heartbeat.cpu,
                ram = heartbeat.ram,
                temp = heartbeat.temp,
                currentContent = heartbeat.currentContent,
                currentAsset = heartbeat.currentAsset,
                currentPlaylistName = heartbeat.currentPlaylistName,
                playbackStatus = heartbeat.playbackStatus,
                playbackUptimeSeconds = heartbeat.playbackUptimeSeconds,
                ip = heartbeat.ip,
                macAddress = heartbeat.macAddress,
                resolution = heartbeat.resolution,
                orientation = heartbeat.orientation,
                timezone = heartbeat.timezone,
                androidVersion = heartbeat.androidVersion,
                playerVersion = heartbeat.playerVersion,
                deviceModel = heartbeat.deviceModel,
                manufacturer = heartbeat.manufacturer,
                deviceName = heartbeat.deviceName,
                lastSyncTime = heartbeat.lastSyncTime,
                storageTotalBytes = heartbeat.storageTotalBytes,
                storageFreeBytes = heartbeat.storageFreeBytes,
                networkStatus = heartbeat.networkStatus,
                permissions = heartbeat.permissions,
                completedCommandId = completedCommandId,
                commandFailed = if (commandFailed) true else null,
                commandError = commandError
            )
            val response = api.submitDeviceReport(token = token, body = body)
            mapDeviceReportResponse(response)
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "Device report failed: HTTP ${e.code()} $errorBody", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Device report failed: ${e.message}", e)
            null
        }
    }

    suspend fun submitSystemLogs(
        category: String,
        message: String,
        metadata: Map<String, Any>? = null
    ): Boolean {
        if (!sessionGuard.isPairedWithToken()) return false
        val token = sessionGuard.requirePairedToken()
        return try {
            api.submitSystemLogs(
                token = token,
                body = SystemLogsRequest(
                    logs = listOf(
                        SystemLogEntry(
                            category = category,
                            message = message,
                            metadata = metadata
                        )
                    )
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "System log upload failed: ${e.message}", e)
            false
        }
    }

    private fun mapDeviceReportResponse(response: DeviceReportResponse): DeviceReportResponse {
        if (!response.commands.isNullOrEmpty()) return response
        val pending = response.pendingCommand ?: return response
        val command = RemoteCommand(
            id = pending.id,
            type = pending.command,
            params = pending.params
        )
        return response.copy(commands = listOf(command))
    }

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
