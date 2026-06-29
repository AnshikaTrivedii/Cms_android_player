package com.orion.player.data.repository

import com.orion.player.data.cache.CacheDebugInfo
import com.orion.player.data.cache.CacheDownloadLogger
import com.orion.player.data.cache.CacheDownloadStatus
import com.orion.player.data.cache.CachedAssetEntry
import com.orion.player.data.cache.ContentCacheManager
import com.orion.player.data.local.CachedAssetEntity
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.sync.PlaybackMode
import com.orion.player.util.NetworkMonitor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates playlist metadata, on-disk cache files, and network state for debug UI and diagnostics.
 */
@Singleton
class ContentCacheRepository @Inject constructor(
    private val playlistCacheRepository: PlaylistCacheRepository,
    private val contentRepository: ContentRepository,
    private val contentCacheManager: ContentCacheManager,
    private val networkMonitor: NetworkMonitor
) {
    private var lastDownloadProgress: String? = null

    fun setDownloadProgress(completed: Int, total: Int) {
        lastDownloadProgress = if (total > 0) "$completed / $total" else null
    }

    fun clearDownloadProgress() {
        lastDownloadProgress = null
    }

    suspend fun getCacheDebugInfo(): CacheDebugInfo {
        val stats = contentCacheManager.getCacheStats()
        val cachedPlaylist = playlistCacheRepository.getPlaylistMetadata()
        val roomAssets = playlistCacheRepository.getCachedAssetEntities()
        val localFiles = buildLocalFileMap(roomAssets)

        val assets = roomAssets.map { entity ->
            val asset = entity.toAssetInfo()
            val file = localFiles[entity.assetId]
            val status = when {
                file != null && file.exists() && file.length() > 0L -> CacheDownloadStatus.CACHED
                else -> CacheDownloadStatus.MISSING
            }
            CachedAssetEntry(
                assetId = entity.assetId,
                name = entity.assetName,
                type = entity.assetType,
                position = entity.position,
                fileSizeBytes = file?.length() ?: 0L,
                expectedFileSize = entity.fileSize,
                localPath = file?.absolutePath,
                status = status,
                downloadedAt = entity.downloadTimestamp.takeIf { it > 0L }
            )
        }.sortedBy { it.position }

        val playable = assets.count { it.status == CacheDownloadStatus.CACHED }
        val online = networkMonitor.isOnline

        return CacheDebugInfo(
            cacheStats = stats,
            playlistId = cachedPlaylist?.playlistId,
            playlistName = cachedPlaylist?.playlistName,
            playlistVersion = cachedPlaylist?.playlistVersion,
            lastSyncTime = cachedPlaylist?.lastSyncTime,
            lastSyncFormatted = formatTimestamp(cachedPlaylist?.lastSyncTime),
            isOnline = online,
            downloadProgress = lastDownloadProgress,
            assets = assets,
            playableCount = playable,
            missingCount = assets.size - playable
        )
    }

    suspend fun validateCurrentPlaylistCache(): com.orion.player.data.cache.CacheValidationResult {
        val snapshot = playlistCacheRepository.loadSnapshot() ?: return com.orion.player.data.cache.CacheValidationResult(
            totalAssets = 0,
            missingAssetIds = emptyList(),
            missingAssetNames = emptyList()
        )
        if (snapshot.mode != PlaybackMode.FULL_SCREEN) {
            return com.orion.player.data.cache.CacheValidationResult(0, emptyList(), emptyList())
        }
        val assets = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        return contentCacheManager.validateAssets(assets, snapshot.localFiles)
    }

    fun logCacheSummary() {
        val stats = contentCacheManager.getCacheStats()
        CacheDownloadLogger.logCacheDirectory(
            stats.directoryPath,
            stats.totalSizeBytes,
            stats.fileCount
        )
    }

    private fun buildLocalFileMap(entities: List<CachedAssetEntity>): Map<String, File> =
        buildMap {
            for (entity in entities) {
                val asset = entity.toAssetInfo()
                val stored = entity.localFilePath?.let { File(it) }?.takeIf { it.exists() }
                val file = stored ?: contentRepository.findCachedFileForAsset(asset)
                if (file != null && file.exists() && file.length() > 0L) {
                    put(entity.assetId, file)
                }
            }
        }

    private fun CachedAssetEntity.toAssetInfo() = AssetInfo.fromCache(
        id = assetId,
        name = assetName,
        type = assetType,
        mimeType = mimeType,
        durationSeconds = durationSeconds,
        position = position,
        downloadUrl = downloadUrl,
        fileSize = fileSize,
        url = remoteUrl,
        assetVersion = fileVersion.toIntOrNull()
    )

    private fun formatTimestamp(epochMs: Long?): String {
        if (epochMs == null || epochMs <= 0L) return "Never"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
    }
}
