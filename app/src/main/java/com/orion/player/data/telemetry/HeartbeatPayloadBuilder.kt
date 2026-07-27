package com.orion.player.data.telemetry

import com.orion.player.data.enterprise.DeviceHealthReporter
import com.orion.player.data.enterprise.DeviceMetadataCollector
import com.orion.player.data.enterprise.DevicePermissionReporter
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
    private val healthMonitor: PlayerHealthMonitor
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
                orientation = metadata.orientation,
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
                permissions = devicePermissionReporter.toHeartbeatPayload()
            )
        )
    }
}
