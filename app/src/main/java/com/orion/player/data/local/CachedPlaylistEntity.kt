package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted playlist manifest for offline playback.
 */
@Entity(tableName = "cached_playlist")
data class CachedPlaylistEntity(
    @PrimaryKey val id: Int = 1,
    val playlistId: String,
    val playlistName: String,
    val campaignName: String?,
    val contentRevision: String?,
    val lastSyncTime: Long
)
