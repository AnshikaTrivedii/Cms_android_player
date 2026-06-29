package com.orion.player.data.analytics

import com.orion.player.data.local.PopLogEntity
import com.orion.player.data.remote.PopLogEntry
import java.time.Duration
import java.time.Instant

/**
 * Detailed Proof-of-Play record for a single asset playback session.
 */
data class PopLogRecord(
    val deviceName: String,
    val playlistName: String,
    val assetName: String,
    val startTime: Instant,
    val endTime: Instant,
    val durationSeconds: Int,
    val status: String
) {
    fun toEntity(): PopLogEntity = PopLogEntity(
        deviceName = deviceName,
        playlistName = playlistName,
        assetName = assetName,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        durationSeconds = durationSeconds,
        status = status
    )

    fun toApiEntry(): PopLogEntry = PopLogEntry(
        assetName = assetName,
        playlistName = playlistName,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        durationSeconds = durationSeconds,
        status = status
    )

    companion object {
        fun verified(
            deviceName: String,
            playlistName: String,
            assetName: String,
            startTime: Instant,
            endTime: Instant,
            durationSeconds: Int = Duration.between(startTime, endTime).seconds.toInt().coerceAtLeast(0)
        ): PopLogRecord {
            return PopLogRecord(
                deviceName = deviceName,
                playlistName = playlistName,
                assetName = assetName,
                startTime = startTime,
                endTime = endTime,
                durationSeconds = durationSeconds.coerceAtLeast(0),
                status = "VERIFIED"
            )
        }

        fun failed(
            deviceName: String,
            playlistName: String,
            assetName: String,
            startTime: Instant,
            endTime: Instant = startTime
        ): PopLogRecord = PopLogRecord(
            deviceName = deviceName,
            playlistName = playlistName,
            assetName = assetName,
            startTime = startTime,
            endTime = endTime,
            durationSeconds = 0,
            status = "FAILED"
        )
    }
}
