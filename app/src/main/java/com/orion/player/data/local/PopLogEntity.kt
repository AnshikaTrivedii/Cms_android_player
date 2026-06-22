package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for offline Proof-of-Play log queue.
 * Stores detailed playback analytics and flushes to the backend when online.
 */
@Entity(tableName = "pop_logs")
data class PopLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceName: String,
    val playlistName: String,
    val campaignName: String,
    val assetName: String,
    val startTime: String,       // ISO 8601
    val endTime: String,         // ISO 8601
    val durationSeconds: Int,
    val status: String,          // "VERIFIED" or "FAILED"
    val isSynced: Boolean = false
)
