package com.orion.player.data.remote

import com.google.gson.annotations.SerializedName
import com.orion.player.data.ticker.TickerInfo

/**
 * API request/response data classes matching the Orion Player API contracts.
 */

// ── Pairing ────────────────────────────────────────────────

data class InitPairingRequest(
    @SerializedName("hardwareId") val hardwareId: String,
    @SerializedName("androidVersion") val androidVersion: String? = null,
    @SerializedName("playerVersion") val playerVersion: String? = null,
    @SerializedName("manufacturer") val manufacturer: String? = null,
    @SerializedName("deviceModel") val deviceModel: String? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("ip") val ip: String? = null,
    @SerializedName("macAddress") val macAddress: String? = null,
    @SerializedName("resolution") val resolution: String? = null,
    @SerializedName("orientation") val orientation: String? = null,
    @SerializedName("timezone") val timezone: String? = null
)

data class InitPairingResponse(
    @SerializedName("hardwareId") val hardwareId: String,
    @SerializedName("isPaired") val isPaired: Boolean,
    @SerializedName("pairingCode") val pairingCode: String?,
    @SerializedName("pairingSecret") val pairingSecret: String? = null
)

data class PairingStatusResponse(
    @SerializedName("isPaired") val isPaired: Boolean,
    @SerializedName("deviceToken") val deviceToken: String?,
    @SerializedName("organizationId") val organizationId: String?,
    @SerializedName("deviceName") val deviceName: String?
)

// ── Heartbeat ──────────────────────────────────────────────

/** Permission flags matching the CMS HeartbeatDto.permissions shape. */
data class DevicePermissionsPayload(
    val internet: Boolean? = null,
    val storage: Boolean? = null,
    val foregroundService: Boolean? = null,
    val bootReceiver: Boolean? = null,
    val wakeLock: Boolean? = null,
    val notification: Boolean? = null,
    val batteryOptimizationDisabled: Boolean? = null,
    val autoStart: Boolean? = null,
    val kioskMode: Boolean? = null,
    val defaultHome: Boolean? = null,
    val deviceOwner: Boolean? = null
)

data class HeartbeatRequest(
    val cpu: Int,
    val ram: Int,
    val temp: Int,
    val currentContent: String? = null,
    val currentAsset: String? = null,
    val currentPlaylistName: String? = null,
    val playbackStatus: String? = null,
    val playbackUptimeSeconds: Long? = null,
    val ip: String? = null,
    val macAddress: String? = null,
    val resolution: String? = null,
    val orientation: String? = null,
    val timezone: String? = null,
    val androidVersion: String? = null,
    val playerVersion: String? = null,
    val deviceModel: String? = null,
    val manufacturer: String? = null,
    val deviceName: String? = null,
    val lastSyncTime: String? = null,
    val storageTotalBytes: Long? = null,
    val storageFreeBytes: Long? = null,
    val networkStatus: String? = null,
    val stretchToFit: Boolean? = null,
    val defaultImageDuration: Int? = null,
    val defaultDocumentDuration: Int? = null,
    val defaultUrlDuration: Int? = null,
    val defaultVideoDuration: Int? = null,
    val playback: PlayerPlaybackDurations? = null,
    val permissions: DevicePermissionsPayload? = null,
    val popPendingCount: Int? = null,
    val popLastGeneratedAt: String? = null,
    val popLastUploadedAt: String? = null,
    val popLastError: String? = null
)

data class PlayerPlaybackDurations(
    val imageDuration: Int? = null,
    val documentDuration: Int? = null,
    val urlDuration: Int? = null,
    val videoDuration: Int? = null,
    /** Aliases matching CMS field names on some payloads. */
    val defaultImageDuration: Int? = null,
    val defaultDocumentDuration: Int? = null,
    val defaultUrlDuration: Int? = null,
    val defaultVideoDuration: Int? = null
)

data class DisplayConfig(
    val orientation: String? = null,
    val stretchToFit: Boolean? = null,
    val playback: PlayerPlaybackDurations? = null,
    val defaultImageDuration: Int? = null,
    val defaultDocumentDuration: Int? = null,
    val defaultUrlDuration: Int? = null,
    val defaultVideoDuration: Int? = null
)

data class HeartbeatResponse(
    val status: String,
    val deviceStatus: String? = null,
    val contentRevision: String? = null,
    val syncRequired: Boolean? = null,
    val commands: List<com.orion.player.data.enterprise.RemoteCommand>? = null,
    val pendingCommand: PendingRemoteCommand? = null,
    val command: String? = null,
    val commandId: String? = null,
    val popLogsExpected: Boolean? = null,
    val features: PlayerFeatures? = null,
    val configVersion: Int? = null,
    val syncIntervalSeconds: Int? = null,
    val revisionPollIntervalSeconds: Int? = null,
    val initialSyncPending: Boolean? = null,
    val initialSyncTimeoutSeconds: Int? = null,
    val stretchToFit: Boolean? = null,
    val orientation: String? = null,
    val display: DisplayConfig? = null,
    val playback: PlayerPlaybackDurations? = null,
    val defaultImageDuration: Int? = null,
    val defaultDocumentDuration: Int? = null,
    val defaultUrlDuration: Int? = null,
    val defaultVideoDuration: Int? = null,
    val activeSchedule: ActiveScheduleInfo? = null,
    val serverTime: String? = null
)

data class PlayerFeatures(
    val autoSync: Boolean? = null,
    val offlinePlayback: Boolean? = null,
    val proofOfPlay: Boolean? = null,
    val ticker: Boolean? = null,
    val watchdog: Boolean? = null,
    val crashRecovery: Boolean? = null,
    val backgroundSync: Boolean? = null,
    val autoDownload: Boolean? = null,
    val remoteLogs: Boolean? = null
)

data class PendingRemoteCommand(
    val id: String? = null,
    val command: String,
    val params: Map<String, String>? = null
)

// ── Sync revision (lightweight change detection) ───────────

data class SyncRevisionResponse(
    val revision: String,
    val deviceStatus: String? = null,
    val updatedAt: String? = null,
    val syncRequired: Boolean = false,
    val playlistVersion: Int? = null,
    val layoutVersion: Int? = null,
    val contentType: String? = null,
    val playlistId: String? = null,
    val layoutId: String? = null,
    val initialSyncPending: Boolean = false,
    val revisionPollIntervalSeconds: Int = 5,
    val syncIntervalSeconds: Int = 120,
    val stretchToFit: Boolean? = null,
    val orientation: String? = null,
    val display: DisplayConfig? = null,
    val playback: PlayerPlaybackDurations? = null,
    val defaultImageDuration: Int? = null,
    val defaultDocumentDuration: Int? = null,
    val defaultUrlDuration: Int? = null,
    val defaultVideoDuration: Int? = null,
    val activeSchedule: ActiveScheduleInfo? = null,
    val configVersion: Int? = null,
    val serverTime: String? = null
)

// ── Sync ───────────────────────────────────────────────────

data class SyncResponse(
    @SerializedName("unchanged") private val unchangedRaw: Boolean? = null,
    @SerializedName("deviceStatus") val deviceStatus: String? = null,
    @SerializedName("playlistVersion") val playlistVersion: Int? = null,
    @SerializedName("playlist") val playlist: PlaylistInfo? = null,
    @SerializedName("layoutVersion") val layoutVersion: Int? = null,
    @SerializedName("layout") val layout: LayoutInfo? = null,
    @SerializedName("assets") val assets: List<AssetInfo>? = null,
    @SerializedName("tickers") val tickers: List<TickerInfo>? = null,
    @SerializedName("currentAssetIds") val currentAssetIds: List<String>? = null,
    @SerializedName("removedAssetIds") val removedAssetIds: List<String>? = null,
    @SerializedName("commands") val commands: List<com.orion.player.data.enterprise.RemoteCommand>? = null,
    @SerializedName("popLogsExpected") val popLogsExpected: Boolean? = null,
    @SerializedName("features") val features: PlayerFeatures? = null,
    @SerializedName("configVersion") val configVersion: Int? = null,
    @SerializedName("syncRequired") val syncRequired: Boolean? = null,
    @SerializedName("pendingDownloadCount") val pendingDownloadCount: Int? = null,
    @SerializedName("contentRevision") val contentRevision: String? = null,
    @SerializedName("cacheCommand") val cacheCommand: CacheCommandInfo? = null,
    @SerializedName("pendingCommand") val pendingCommand: PendingRemoteCommand? = null,
    @SerializedName("syncIntervalSeconds") val syncIntervalSeconds: Int? = null,
    @SerializedName("revisionPollIntervalSeconds") val revisionPollIntervalSeconds: Int? = null,
    @SerializedName("initialSyncPending") val initialSyncPending: Boolean? = null,
    @SerializedName("initialSyncTimeoutSeconds") val initialSyncTimeoutSeconds: Int? = null,
    @SerializedName("stretchToFit") val stretchToFit: Boolean? = null,
    @SerializedName("orientation") val orientation: String? = null,
    @SerializedName("display") val display: DisplayConfig? = null,
    @SerializedName("playback") val playback: PlayerPlaybackDurations? = null,
    @SerializedName("defaultImageDuration") val defaultImageDuration: Int? = null,
    @SerializedName("defaultDocumentDuration") val defaultDocumentDuration: Int? = null,
    @SerializedName("defaultUrlDuration") val defaultUrlDuration: Int? = null,
    @SerializedName("defaultVideoDuration") val defaultVideoDuration: Int? = null,
    @SerializedName("activeSchedule") val activeSchedule: ActiveScheduleInfo? = null,
    @SerializedName("serverTime") val serverTime: String? = null
) {
    val unchanged: Boolean get() = unchangedRaw ?: false
    fun resolvedAssets(): List<AssetInfo> = assets.orEmpty().filter { it.id.isNotBlank() }
    fun resolvedTickers(): List<TickerInfo> = tickers.orEmpty()
    fun resolvedCurrentAssetIds(): Set<String> = currentAssetIds.orEmpty().toSet()
    fun resolvedRemovedAssetIds(): Set<String> = removedAssetIds.orEmpty().toSet()
    val isLayoutMode: Boolean get() = layout != null

    /** Treat inline assets as a fresh manifest even when the server marked the response unchanged. */
    fun withUpdatedManifest(assets: List<AssetInfo>): SyncResponse =
        copy(unchangedRaw = false, assets = assets)
}

data class PlaylistInfo(
    @SerializedName("id") private val idRaw: String? = null,
    @SerializedName("name") private val nameRaw: String? = null
) {
    val id: String get() = idRaw.orEmpty()
    val name: String get() = nameRaw.orEmpty()

    companion object {
        fun of(id: String, name: String) = PlaylistInfo(idRaw = id, nameRaw = name)
    }
}

/**
 * Schedule the CMS reports as currently active for this device. Null means no
 * schedule is running and the manually assigned playlist applies.
 *
 * The CMS still chooses which schedule is active. [startDateTime] / [endDateTime]
 * are the server-provided window for that choice: naive timestamps are Asia/Kolkata,
 * ISO-8601 with Z/offset is UTC. The player uses them only as a safety net so an
 * expired payload is never cached or played as active.
 */
data class ActiveScheduleInfo(
    @SerializedName("scheduleId") private val scheduleIdRaw: String? = null,
    @SerializedName("id") private val idRaw: String? = null,
    @SerializedName("playlistId") private val playlistIdRaw: String? = null,
    @SerializedName("playlistName") private val playlistNameRaw: String? = null,
    @SerializedName("name") private val nameRaw: String? = null,
    @SerializedName("startDateTime") val startDateTime: String? = null,
    @SerializedName("endDateTime") val endDateTime: String? = null,
    @SerializedName("startTime") val startTime: String? = null,
    @SerializedName("endTime") val endTime: String? = null,
    @SerializedName("start") val start: String? = null,
    @SerializedName("end") val end: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("timezone") val timezone: String? = null
) {
    val scheduleId: String get() = (scheduleIdRaw ?: idRaw).orEmpty()
    val playlistId: String get() = playlistIdRaw.orEmpty()
    val playlistName: String get() = (playlistNameRaw ?: nameRaw).orEmpty()

    val resolvedStart: String?
        get() = startDateTime?.takeIf { it.isNotBlank() }
            ?: startTime?.takeIf { it.isNotBlank() }
            ?: start?.takeIf { it.isNotBlank() }

    val resolvedEnd: String?
        get() = endDateTime?.takeIf { it.isNotBlank() }
            ?: endTime?.takeIf { it.isNotBlank() }
            ?: end?.takeIf { it.isNotBlank() }

    val isTerminalStatus: Boolean
        get() {
            val value = status?.trim()?.uppercase() ?: return false
            return value in TERMINAL_STATUSES
        }

    /** Ignore empty objects so `activeSchedule: {}` behaves like `null`. */
    val isPresent: Boolean get() = scheduleId.isNotBlank() || playlistId.isNotBlank()

    companion object {
        private val TERMINAL_STATUSES = setOf(
            "COMPLETED", "EXPIRED", "DISABLED", "INACTIVE", "ENDED",
            "CANCELLED", "CANCELED", "STOPPED"
        )
    }
}

data class AssetInfo(
    @SerializedName("id") private val idRaw: String? = null,
    @SerializedName("name") private val nameRaw: String? = null,
    @SerializedName("type") private val typeRaw: String? = null,
    @SerializedName("mimeType") private val mimeTypeRaw: String? = null,
    @SerializedName("durationSeconds") private val durationSecondsRaw: Int? = null,
    @SerializedName("position") private val positionRaw: Int? = null,
    @SerializedName("downloadUrl") val downloadUrl: String? = null,
    @SerializedName("fileSize") private val fileSizeRaw: Int? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("assetVersion") private val assetVersionRaw: Int? = null,
    @SerializedName("contentHash") val contentHash: String? = null,
    @SerializedName("updatedAt") val updatedAt: String? = null,
    @SerializedName("requiresDownload") private val requiresDownloadRaw: Boolean? = null,
    @SerializedName("available") private val availableRaw: Boolean? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("unavailableReason") val unavailableReason: String? = null,
    @SerializedName("documentFormat") val documentFormat: String? = null
) {
    val id: String get() = idRaw.orEmpty()
    val name: String get() = nameRaw.orEmpty()
    val type: String get() = typeRaw?.takeIf { it.isNotBlank() } ?: "IMAGE"
    val mimeType: String get() = mimeTypeRaw?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    /**
     * Playlist slot duration from CMS. Null means "use device playback settings"
     * (or natural end for video). Never invents 10/15/20.
     */
    val durationSeconds: Int? get() = durationSecondsRaw?.takeIf { it > 0 }
    val position: Int get() = positionRaw ?: 0
    /** Raw CMS duration; null when the server omitted the field or sent null. */
    val cmsDurationSeconds: Int? get() = durationSecondsRaw?.takeIf { it > 0 }
    val fileSize: Int get() = fileSizeRaw ?: 0
    val assetVersion: Int? get() = assetVersionRaw
    val requiresDownload: Boolean get() = requiresDownloadRaw ?: true
    val available: Boolean get() = availableRaw ?: true

    /** Merge manifest + zone-embedded copies; prefer non-empty download fields from either side. */
    fun mergeWith(other: AssetInfo): AssetInfo {
        if (id.isBlank() && other.id.isNotBlank()) return other
        if (other.id.isNotBlank() && id != other.id) return this
        return AssetInfo(
            idRaw = id.ifBlank { other.id },
            nameRaw = name.takeIf { it.isNotBlank() } ?: other.name,
            typeRaw = type.takeIf { it.isNotBlank() } ?: other.type,
            mimeTypeRaw = mimeType.takeIf { it.isNotBlank() } ?: other.mimeType,
            // Prefer an explicit positive duration from either side; only stay null when both are null/blank.
            // Do not invent 10. Allow null (blank playlist duration) to win when the other side is also blank.
            durationSecondsRaw = listOfNotNull(
                durationSecondsRaw?.takeIf { it > 0 },
                other.durationSecondsRaw?.takeIf { it > 0 }
            ).firstOrNull(),
            positionRaw = positionRaw ?: other.positionRaw,
            downloadUrl = downloadUrl?.takeIf { it.isNotBlank() } ?: other.downloadUrl,
            fileSizeRaw = fileSizeRaw?.takeIf { it > 0 } ?: other.fileSizeRaw,
            url = url?.takeIf { it.isNotBlank() } ?: other.url,
            assetVersionRaw = assetVersionRaw ?: other.assetVersionRaw,
            contentHash = contentHash ?: other.contentHash,
            updatedAt = updatedAt ?: other.updatedAt,
            requiresDownloadRaw = requiresDownloadRaw ?: other.requiresDownloadRaw,
            availableRaw = availableRaw ?: other.availableRaw,
            status = status ?: other.status,
            unavailableReason = unavailableReason ?: other.unavailableReason,
            documentFormat = documentFormat ?: other.documentFormat
        )
    }

    companion object {
        /** Reconstruct from Room cache (not from Gson). Null duration is preserved. */
        fun fromCache(
            id: String,
            name: String,
            type: String,
            mimeType: String,
            durationSeconds: Int?,
            position: Int,
            downloadUrl: String?,
            fileSize: Int,
            url: String?,
            assetVersion: Int? = null
        ): AssetInfo = AssetInfo(
            idRaw = id,
            nameRaw = name,
            typeRaw = type,
            mimeTypeRaw = mimeType,
            // Preserve null; never coerce sentinel/legacy values into a fake duration.
            durationSecondsRaw = durationSeconds?.takeIf { it > 0 },
            positionRaw = position,
            downloadUrl = downloadUrl,
            fileSizeRaw = fileSize,
            url = url,
            assetVersionRaw = assetVersion
        )
    }
}

// ── Proof of Play ──────────────────────────────────────────

/**
 * Payload sent to POST /player/pop-logs.
 * Device identity comes from the Authorization header — do not send deviceName.
 */
data class PopLogEntry(
    val assetName: String? = null,
    val content: String? = null,        // legacy alias for assetName
    val playlistName: String? = null,
    val playlistId: String? = null,
    val assetId: String? = null,
    val deviceId: String? = null,
    val status: String,                 // "VERIFIED" or "FAILED"
    val startTime: String? = null,      // ISO 8601
    val endTime: String? = null,        // ISO 8601
    val durationSeconds: Int? = null,
    val timestamp: String? = null       // legacy alias for startTime
)

data class PopLogsRequest(
    val logs: List<PopLogEntry>
)

data class PopLogsResponse(
    val received: Int,
    val skipped: Int? = null,
    val accepted: Boolean? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val popLogsExpected: Boolean? = null,
    val reason: String? = null
)

// ── Cache command & report ─────────────────────────────────

data class CacheCommandInfo(
    val id: String? = null,
    val command: String
)

data class CacheReportAsset(
    val assetId: String,
    val assetName: String,
    val assetType: String,
    val mimeType: String? = null,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val fileSize: Int? = null,
    val assetVersion: Int? = null,
    val contentHash: String? = null,
    val downloadStatus: String,
    val localCacheStatus: String,
    val downloadedAt: String? = null
)

data class CacheReportRequest(
    val currentPlaylistId: String? = null,
    val currentPlaylistName: String? = null,
    val playlistVersion: Int? = null,
    val currentLayoutId: String? = null,
    val currentLayoutName: String? = null,
    val layoutVersion: Int? = null,
    val cacheTotalBytes: Long? = null,
    val cacheUsedBytes: Long? = null,
    val cachedAssetCount: Int? = null,
    val expectedAssetCount: Int? = null,
    val pendingDownloadCount: Int? = null,
    val syncStatus: String? = null,
    val lastSuccessfulSyncAt: String? = null,
    val lastFailedSyncAt: String? = null,
    val lastSyncError: String? = null,
    val completedCommandId: String? = null,
    val commandFailed: Boolean? = null,
    val commandError: String? = null,
    val assets: List<CacheReportAsset> = emptyList()
)

// ── Device report & system logs ─────────────────────────────

data class DeviceReportRequest(
    val cpu: Int,
    val ram: Int,
    val temp: Int,
    val currentContent: String? = null,
    val currentAsset: String? = null,
    val currentPlaylistName: String? = null,
    val playbackStatus: String? = null,
    val playbackUptimeSeconds: Long? = null,
    val ip: String? = null,
    val macAddress: String? = null,
    val resolution: String? = null,
    val orientation: String? = null,
    val timezone: String? = null,
    val androidVersion: String? = null,
    val playerVersion: String? = null,
    val deviceModel: String? = null,
    val manufacturer: String? = null,
    val deviceName: String? = null,
    val lastSyncTime: String? = null,
    val storageTotalBytes: Long? = null,
    val storageFreeBytes: Long? = null,
    val networkStatus: String? = null,
    val permissions: DevicePermissionsPayload? = null,
    val completedCommandId: String? = null,
    val commandFailed: Boolean? = null,
    val commandError: String? = null
)

data class DeviceReportResponse(
    val received: Boolean? = null,
    val configVersion: Int? = null,
    val popLogsExpected: Boolean? = null,
    val syncIntervalSeconds: Int? = null,
    val initialSyncPending: Boolean? = null,
    val initialSyncTimeoutSeconds: Int? = null,
    val features: PlayerFeatures? = null,
    val commands: List<com.orion.player.data.enterprise.RemoteCommand>? = null,
    val pendingCommand: PendingRemoteCommand? = null
)

data class SystemLogEntry(
    val category: String,
    val message: String,
    val metadata: Map<String, Any>? = null
)

data class SystemLogsRequest(
    val logs: List<SystemLogEntry>
)

data class SystemLogsResponse(
    val received: Int? = null
)
