package com.orion.player.data.remote

import com.google.gson.annotations.SerializedName
import com.orion.player.data.ticker.TickerInfo

/**
 * API request/response data classes matching the Orion Player API contracts.
 */

// ── Pairing ────────────────────────────────────────────────

data class InitPairingRequest(
    @SerializedName("hardwareId") val hardwareId: String
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

data class HeartbeatRequest(
    val cpu: Int,
    val ram: Int,
    val temp: Int,
    val currentContent: String? = null
)

data class HeartbeatResponse(
    val status: String,
    val contentRevision: String? = null,
    val syncRequired: Boolean? = null
)

// ── Sync revision (lightweight change detection) ───────────

data class SyncRevisionResponse(
    val revision: String,
    val updatedAt: String? = null
)

// ── Sync ───────────────────────────────────────────────────

data class SyncResponse(
    @SerializedName("unchanged") private val unchangedRaw: Boolean? = null,
    @SerializedName("playlistVersion") val playlistVersion: Int? = null,
    @SerializedName("playlist") val playlist: PlaylistInfo? = null,
    @SerializedName("layoutVersion") val layoutVersion: Int? = null,
    @SerializedName("layout") val layout: LayoutInfo? = null,
    @SerializedName("assets") val assets: List<AssetInfo>? = null,
    @SerializedName("tickers") val tickers: List<TickerInfo>? = null,
    @SerializedName("currentAssetIds") val currentAssetIds: List<String>? = null,
    @SerializedName("removedAssetIds") val removedAssetIds: List<String>? = null
) {
    val unchanged: Boolean get() = unchangedRaw ?: false
    fun resolvedAssets(): List<AssetInfo> = assets.orEmpty().filter { it.id.isNotBlank() }
    fun resolvedTickers(): List<TickerInfo> = tickers.orEmpty()
    fun resolvedCurrentAssetIds(): Set<String> = currentAssetIds.orEmpty().toSet()
    fun resolvedRemovedAssetIds(): Set<String> = removedAssetIds.orEmpty().toSet()
    val isLayoutMode: Boolean get() = layout != null
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
    @SerializedName("requiresDownload") private val requiresDownloadRaw: Boolean? = null
) {
    val id: String get() = idRaw.orEmpty()
    val name: String get() = nameRaw.orEmpty()
    val type: String get() = typeRaw?.takeIf { it.isNotBlank() } ?: "IMAGE"
    val mimeType: String get() = mimeTypeRaw?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    val durationSeconds: Int get() = durationSecondsRaw?.coerceAtLeast(1) ?: 10
    val position: Int get() = positionRaw ?: 0
    val fileSize: Int get() = fileSizeRaw ?: 0
    val assetVersion: Int? get() = assetVersionRaw
    val requiresDownload: Boolean get() = requiresDownloadRaw ?: true

    /** Merge manifest + zone-embedded copies; prefer non-empty download fields from either side. */
    fun mergeWith(other: AssetInfo): AssetInfo {
        if (id.isBlank() && other.id.isNotBlank()) return other
        if (other.id.isNotBlank() && id != other.id) return this
        return AssetInfo(
            idRaw = id.ifBlank { other.id },
            nameRaw = name.takeIf { it.isNotBlank() } ?: other.name,
            typeRaw = type.takeIf { it.isNotBlank() } ?: other.type,
            mimeTypeRaw = mimeType.takeIf { it.isNotBlank() } ?: other.mimeType,
            durationSecondsRaw = durationSecondsRaw ?: other.durationSecondsRaw,
            positionRaw = positionRaw ?: other.positionRaw,
            downloadUrl = downloadUrl?.takeIf { it.isNotBlank() } ?: other.downloadUrl,
            fileSizeRaw = fileSizeRaw?.takeIf { it > 0 } ?: other.fileSizeRaw,
            url = url?.takeIf { it.isNotBlank() } ?: other.url,
            assetVersionRaw = assetVersionRaw ?: other.assetVersionRaw,
            contentHash = contentHash ?: other.contentHash,
            updatedAt = updatedAt ?: other.updatedAt,
            requiresDownloadRaw = requiresDownloadRaw ?: other.requiresDownloadRaw
        )
    }

    companion object {
        /** Reconstruct from Room cache (not from Gson). */
        fun fromCache(
            id: String,
            name: String,
            type: String,
            mimeType: String,
            durationSeconds: Int,
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
            durationSecondsRaw = durationSeconds,
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
    val received: Int
)
