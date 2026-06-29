package com.orion.player.data.cache

enum class CacheDownloadStatus {
    CACHED,
    MISSING,
    DOWNLOADING,
    FAILED
}

data class CachedAssetEntry(
    val assetId: String,
    val name: String,
    val type: String,
    val position: Int,
    val fileSizeBytes: Long,
    val expectedFileSize: Int,
    val localPath: String?,
    val status: CacheDownloadStatus,
    val downloadedAt: Long?
)

data class ContentCacheStats(
    val directoryPath: String,
    val totalSizeBytes: Long,
    val fileCount: Int
)

data class CacheDebugInfo(
    val cacheStats: ContentCacheStats,
    val playlistId: String?,
    val playlistName: String?,
    val playlistVersion: Int?,
    val lastSyncTime: Long?,
    val lastSyncFormatted: String,
    val isOnline: Boolean,
    val downloadProgress: String?,
    val assets: List<CachedAssetEntry>,
    val playableCount: Int,
    val missingCount: Int
)

data class CacheValidationResult(
    val totalAssets: Int,
    val missingAssetIds: List<String>,
    val missingAssetNames: List<String>
) {
    val hasMissingFiles: Boolean get() = missingAssetIds.isNotEmpty()
}
