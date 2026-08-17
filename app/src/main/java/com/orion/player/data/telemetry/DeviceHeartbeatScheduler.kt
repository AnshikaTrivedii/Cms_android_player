package com.orion.player.data.telemetry

import com.orion.player.data.enterprise.RemoteCommandExecutor
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.remote.HeartbeatResponse
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.data.stability.StabilityMonitor
import com.orion.player.data.sync.InitialSyncCoordinator
import com.orion.player.data.sync.ServerPlayerSignals
import com.orion.player.util.SessionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide heartbeat scheduler. Runs continuously while the device is paired,
 * independent of [com.orion.player.ui.playback.PlaybackViewModel] lifecycle.
 */
@Singleton
class DeviceHeartbeatScheduler @Inject constructor(
    private val telemetryRepository: TelemetryRepository,
    private val sessionGuard: SessionGuard,
    private val securePrefs: SecurePrefs,
    private val heartbeatPayloadBuilder: HeartbeatPayloadBuilder,
    private val healthMonitor: PlayerHealthMonitor,
    private val remoteCommandExecutor: RemoteCommandExecutor,
    private val stabilityMonitor: StabilityMonitor,
    private val initialSyncCoordinator: InitialSyncCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private val _responses = MutableSharedFlow<HeartbeatResponse>(extraBufferCapacity = 8)
    val responses: SharedFlow<HeartbeatResponse> = _responses.asSharedFlow()

    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (heartbeatJob?.isActive == true) return
        val deviceId = securePrefs.getOrCreateHardwareId()
        HeartbeatTelemetryLogger.logScheduled(deviceId, intervalMs)
        heartbeatJob = scope.launch {
            sendHeartbeatNow()
            while (true) {
                delay(intervalMs)
                sendHeartbeatNow()
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    suspend fun sendHeartbeatNow(): HeartbeatResponse? {
        if (!sessionGuard.isPairedWithToken()) {
            HeartbeatTelemetryLogger.logSkipped(
                securePrefs.getOrCreateHardwareId(),
                "not_paired"
            )
            return null
        }

        val currentAsset = healthMonitor.currentAssetName
        val playlistName = healthMonitor.currentPlaylistName
        val body = heartbeatPayloadBuilder.build()

        val response = telemetryRepository.sendHeartbeat(body)
        response?.let { mapped ->
            initialSyncCoordinator.handleHeartbeatResponse(mapped)
            val commands = ServerPlayerSignals.from(mapped).mergedCommands()
            if (commands.isNotEmpty()) {
                remoteCommandExecutor.dispatch(commands)
            }
            _responses.emit(mapped)
        }
        stabilityMonitor.reportIfDue(
            queueSize = healthMonitor.currentQueueSize,
            currentAsset = currentAsset,
            playlistName = playlistName
        )
        return response
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 10 * 60 * 1000L
    }
}
