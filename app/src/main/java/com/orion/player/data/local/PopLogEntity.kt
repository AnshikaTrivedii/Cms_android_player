package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for offline Proof-of-Play log queue.
 * PoP entries are queued locally and flushed to the backend every 5 minutes.
 */
@Entity(tableName = "pop_logs")
data class PopLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,        // Asset name
    val status: String,         // "VERIFIED" or "FAILED"
    val timestamp: String,      // ISO 8601 string
    val isSynced: Boolean = false
)
