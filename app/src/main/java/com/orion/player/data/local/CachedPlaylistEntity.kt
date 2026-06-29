package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted playback manifest for offline-first playback (playlist or layout mode).
 */
@Entity(tableName = "cached_playlist")
data class CachedPlaylistEntity(
    @PrimaryKey val id: Int = 1,
    val playbackMode: String,
    val playlistId: String?,
    val playlistName: String?,
    val playlistVersion: Int?,
    val layoutVersion: Int?,
    val layoutJson: String?,
    val contentRevision: String?,
    val lastSyncTime: Long
)
