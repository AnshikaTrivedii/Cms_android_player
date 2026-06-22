package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Offline queue for device telemetry heartbeats.
 */
@Entity(tableName = "queued_heartbeats")
data class QueuedHeartbeatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cpu: Int,
    val ram: Int,
    val temp: Int,
    val currentContent: String?,
    val recordedAt: Long,
    val isSynced: Boolean = false
)
