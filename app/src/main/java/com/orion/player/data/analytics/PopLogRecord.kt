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
    val campaignName: String,
    val assetName: String,
    val startTime: Instant,
    val endTime: Instant,
    val durationSeconds: Int,
    val status: String
) {
    fun toEntity(): PopLogEntity = PopLogEntity(
        deviceName = deviceName,
        playlistName = playlistName,
        campaignName = campaignName,
        assetName = assetName,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        durationSeconds = durationSeconds,
        status = status
    )

    fun toApiEntry(): PopLogEntry = PopLogEntry(
        assetName = assetName,
        playlistName = playlistName,
        campaignName = campaignName,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        durationSeconds = durationSeconds,
        status = status
    )

    companion object {
        fun verified(
            deviceName: String,
            playlistName: String,
            campaignName: String,
            assetName: String,
            startTime: Instant,
            endTime: Instant
        ): PopLogRecord {
            val duration = Duration.between(startTime, endTime).seconds.toInt().coerceAtLeast(0)
            return PopLogRecord(
                deviceName = deviceName,
                playlistName = playlistName,
                campaignName = campaignName,
                assetName = assetName,
                startTime = startTime,
                endTime = endTime,
                durationSeconds = duration,
                status = "VERIFIED"
            )
        }

        fun failed(
            deviceName: String,
            playlistName: String,
            campaignName: String,
            assetName: String,
            startTime: Instant,
            endTime: Instant = startTime
        ): PopLogRecord = PopLogRecord(
            deviceName = deviceName,
            playlistName = playlistName,
            campaignName = campaignName,
            assetName = assetName,
            startTime = startTime,
            endTime = endTime,
            durationSeconds = 0,
            status = "FAILED"
        )
    }
}
