package com.orion.player.data.telemetry

import com.orion.player.BuildConfig
import com.orion.player.data.remote.HeartbeatRequest

/**
 * Normalizes heartbeat payloads to match CMS validation constraints.
 */
object HeartbeatPayloadNormalizer {
    private const val MAX_INT_BYTES = Int.MAX_VALUE.toLong()

    fun normalize(request: HeartbeatRequest): HeartbeatRequest {
        return request.copy(
            cpu = request.cpu.coerceIn(0, 100),
            ram = request.ram.coerceIn(0, 100),
            temp = when {
                request.temp < 0 -> 0
                request.temp > 120 -> 120
                else -> request.temp
            },
            playbackUptimeSeconds = request.playbackUptimeSeconds?.coerceIn(0L, Int.MAX_VALUE.toLong()),
            storageTotalBytes = request.storageTotalBytes?.coerceIn(0L, MAX_INT_BYTES),
            storageFreeBytes = request.storageFreeBytes?.coerceIn(0L, MAX_INT_BYTES),
            // CMS HeartbeatDto rejects these extra properties.
            playback = null,
            popPendingCount = null,
            popLastGeneratedAt = null,
            popLastUploadedAt = null,
            popLastError = null,
            permissions = request.permissions?.copy(
                defaultHome = null,
                deviceOwner = null
            )
        )
    }

    fun endpointUrl(): String = BuildConfig.BASE_URL.trimEnd('/') + "/player/heartbeat"
}
