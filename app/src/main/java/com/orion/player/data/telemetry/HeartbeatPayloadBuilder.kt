package com.orion.player.data.telemetry

import com.orion.player.data.analytics.PopHealthTracker
import com.orion.player.data.config.DeviceConfigManager
import com.orion.player.data.enterprise.DeviceHealthReporter
import com.orion.player.data.enterprise.DeviceMetadataCollector
import com.orion.player.data.enterprise.DevicePermissionReporter
import com.orion.player.data.local.PopLogDao
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.remote.HeartbeatRequest
import com.orion.player.util.DeviceHealthUtil
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeartbeatPayloadBuilder @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val deviceHealthUtil: DeviceHealthUtil,
    private val deviceHealthReporter: DeviceHealthReporter,
    private val deviceMetadataCollector: DeviceMetadataCollector,
    private val devicePermissionReporter: DevicePermissionReporter,
    private val healthMonitor: PlayerHealthMonitor,
    private val popLogDao: PopLogDao,
    private val popHealthTracker: PopHealthTracker,
    private val deviceConfigManager: DeviceConfigManager
) {
    suspend fun build(): HeartbeatRequest {
        val currentAsset = healthMonitor.currentAssetName
        val playlistName = healthMonitor.currentPlaylistName
        val health = deviceHealthReporter.snapshot(
            playlistName = playlistName,
            currentAsset = currentAsset,
            queueSize = healthMonitor.currentQueueSize
        )
        val metadata = deviceMetadataCollector.heartbeatSnapshot(
            currentAsset = currentAsset,
            currentPlaylistName = playlistName,
            playbackStatus = health.playbackStatus,
            playbackUptimeSeconds = health.uptimeSeconds,
            networkOnline = health.networkOnline
        )
        val pending = runCatching { popLogDao.getUnsyncedCount() }.getOrDefault(0)
        val popHealth = popHealthTracker.snapshot(pending)
        val appliedOrientation = deviceConfigManager.orientation.value.name
        val appliedStretch = deviceConfigManager.stretchToFit.value

        return HeartbeatPayloadNormalizer.normalize(
            HeartbeatRequest(
                cpu = deviceHealthUtil.getCpuUsage(),
                ram = deviceHealthUtil.getRamUsage(),
                temp = deviceHealthUtil.getTemperature(),
                currentContent = currentAsset,
                currentAsset = metadata.currentAsset,
                currentPlaylistName = metadata.currentPlaylistName,
                playbackStatus = metadata.playbackStatus,
                playbackUptimeSeconds = metadata.playbackUptimeSeconds,
                ip = metadata.ip,
                macAddress = metadata.macAddress,
                resolution = metadata.resolution,
                orientation = appliedOrientation,
                timezone = metadata.timezone,
                androidVersion = metadata.androidVersion,
                playerVersion = metadata.playerVersion,
                deviceModel = metadata.deviceModel,
                manufacturer = metadata.manufacturer,
                deviceName = metadata.deviceName,
                lastSyncTime = metadata.lastSyncTime,
                storageTotalBytes = health.storageTotalMb * 1024L * 1024L,
                storageFreeBytes = health.storageFreeMb * 1024L * 1024L,
                networkStatus = metadata.networkStatus,
                stretchToFit = appliedStretch,
                permissions = devicePermissionReporter.toHeartbeatPayload(),
                popPendingCount = popHealth.pendingCount,
                popLastGeneratedAt = popHealth.lastGeneratedAt,
                popLastUploadedAt = popHealth.lastUploadedAt,
                popLastError = popHealth.lastError
            )
        )
    }
}
