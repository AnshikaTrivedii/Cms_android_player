package com.orion.player.data.enterprise

import com.google.gson.annotations.SerializedName

/** Extended health snapshot sent with heartbeat. */
data class DeviceHealthSnapshot(
    val cpuPercent: Int,
    val ramPercent: Int,
    val temperatureCelsius: Int,
    val storageFreeMb: Long,
    val storageTotalMb: Long,
    val cacheSizeMb: Long,
    val cacheFileCount: Int,
    val batteryPercent: Int?,
    val batteryCharging: Boolean?,
    val uptimeSeconds: Long,
    val appVersion: String,
    val playbackStatus: String,
    val currentPlaylist: String?,
    val currentAsset: String?,
    val queueSize: Int,
    val networkOnline: Boolean,
    val threadCount: Int
)

/** Device capability and permission status reported to CMS. */
data class DevicePermissionSnapshot(
    val internet: Boolean,
    val networkState: Boolean,
    val bootReceiver: Boolean,
    val foregroundService: Boolean,
    val wakeLock: Boolean,
    val postNotifications: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val autoStartLikely: Boolean,
    val kioskModeEnabled: Boolean,
    val defaultHome: Boolean,
    val deviceOwner: Boolean
)

/** Remote command issued by CMS via heartbeat or sync response. */
data class RemoteCommand(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String,
    @SerializedName("params") val params: Map<String, String>? = null
)

object RemoteCommandType {
    const val RESTART_PLAYER = "restart_player"
    const val RESTART_DEVICE = "restart_device"
    const val FORCE_SYNC = "force_sync"
    const val CLEAR_CACHE = "clear_cache"
    const val REDOWNLOAD_PLAYLIST = "redownload_playlist"
    const val UPLOAD_LOGS = "upload_logs"
    const val TAKE_SCREENSHOT = "take_screenshot"
}

data class DeviceLogsUploadRequest(
    val logs: String,
    val crashLog: String? = null,
    val deviceName: String? = null,
    val appVersion: String? = null,
    val uploadedAt: String
)

data class DeviceLogsUploadResponse(
    val received: Boolean = true
)
