package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local asset metadata and file path for offline playback.
 */
@Entity(tableName = "cached_assets")
data class CachedAssetEntity(
    @PrimaryKey val assetId: String,
    val assetName: String,
    val assetType: String,
    val mimeType: String,
    val durationSeconds: Int,
    val position: Int,
    val downloadUrl: String?,
    val fileSize: Int,
    val remoteUrl: String?,
    val localFilePath: String?,
    val fileVersion: String,
    val downloadTimestamp: Long
)
