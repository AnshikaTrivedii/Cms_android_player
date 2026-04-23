package com.orion.player.data.remote

/**
 * API request/response data classes matching the Orion Player API contracts.
 */

// ── Pairing ────────────────────────────────────────────────

data class InitPairingRequest(
    val hardwareId: String
)

data class InitPairingResponse(
    val hardwareId: String,
    val isPaired: Boolean,
    val pairingCode: String?
)

data class PairingStatusResponse(
    val isPaired: Boolean,
    val deviceToken: String?,
    val organizationId: String?,
    val deviceName: String?
)

// ── Heartbeat ──────────────────────────────────────────────

data class HeartbeatRequest(
    val cpu: Int,
    val ram: Int,
    val temp: Int,
    val currentContent: String? = null
)

data class HeartbeatResponse(
    val status: String
)

// ── Sync ───────────────────────────────────────────────────

data class SyncResponse(
    val playlist: PlaylistInfo?,
    val assets: List<AssetInfo>
)

data class PlaylistInfo(
    val id: String,
    val name: String
)

data class AssetInfo(
    val id: String,
    val name: String,
    val type: String,           // IMAGE, VIDEO, HTML, DOCUMENT
    val mimeType: String,
    val durationSeconds: Int,
    val position: Int,
    val downloadUrl: String?,
    val fileSize: Int
)

// ── Proof of Play ──────────────────────────────────────────

data class PopLogEntry(
    val content: String,
    val status: String,         // "VERIFIED" or "FAILED"
    val timestamp: String       // ISO 8601
)

data class PopLogsRequest(
    val logs: List<PopLogEntry>
)

data class PopLogsResponse(
    val received: Int
)
