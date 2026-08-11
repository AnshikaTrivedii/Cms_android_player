package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One playlist queue occurrence. [queueIndex] is the playback order slot;
 * [assetId] may repeat when the CMS lists the same asset multiple times.
 * Disk cache is still keyed by [assetId] (one file, many queue rows).
 */
@Entity(tableName = "cached_assets")
data class CachedAssetEntity(
    @PrimaryKey val queueIndex: Int,
    val assetId: String,
    val assetName: String,
    val assetType: String,
    val mimeType: String,
    /** Null = use device playback defaults (or video natural end). Never store fake 10/15/20. */
    val durationSeconds: Int?,
    val position: Int,
    val downloadUrl: String?,
    val fileSize: Int,
    val remoteUrl: String?,
    val localFilePath: String?,
    val fileVersion: String,
    val downloadTimestamp: Long
)
