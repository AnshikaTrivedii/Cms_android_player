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
    val playlist: PlaylistInfo?,
    val campaignName: String? = null,
    val assets: List<AssetInfo>,
    val tickers: List<TickerInfo> = emptyList()
)

data class PlaylistInfo(
    val id: String,
    val name: String
)

data class AssetInfo(
    val id: String,
    val name: String,
    val type: String,           // IMAGE, VIDEO, HTML, DOCUMENT, URL
    val mimeType: String,
    val durationSeconds: Int,
    val position: Int,
    val downloadUrl: String?,
    val fileSize: Int,
    val url: String? = null     // Remote URL for type=URL (http/https only)
)

// ── Proof of Play ──────────────────────────────────────────

/**
 * Payload sent to POST /player/pop-logs.
 * Device identity comes from the Authorization header — do not send deviceName.
 */
data class PopLogEntry(
    val assetName: String,
    val playlistName: String,
    val campaignName: String,
    val startTime: String,      // ISO 8601
    val endTime: String,        // ISO 8601
    val durationSeconds: Int,
    val status: String          // "VERIFIED" or "FAILED"
)

data class PopLogsRequest(
    val logs: List<PopLogEntry>
)

data class PopLogsResponse(
    val received: Int
)
