package com.orion.player.data.repository

import com.orion.player.data.cache.ContentCacheManager
import com.orion.player.data.local.PlaylistCacheDao
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.CacheReportAsset
import com.orion.player.data.remote.CacheReportRequest
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.SyncResponse
import com.orion.player.data.sync.PlaybackSnapshot
import com.orion.player.data.remote.AssetInfo
import com.orion.player.util.SessionGuard
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheReportRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val sessionGuard: SessionGuard,
    private val playlistCacheDao: PlaylistCacheDao,
    private val contentRepository: ContentRepository,
    private val contentCacheManager: ContentCacheManager,
    private val securePrefs: SecurePrefs
) {
    suspend fun reportSuccessfulSync(
        syncResponse: SyncResponse,
        snapshot: PlaybackSnapshot,
        completedCommandId: String? = null,
        commandFailed: Boolean = false,
        commandError: String? = null
    ): Boolean {
        if (!sessionGuard.isPairedWithToken()) return false
        val token = sessionGuard.requirePairedToken()
        val cachedAssets = playlistCacheDao.getAssets()
        val cacheStats = contentRepository.getContentCacheStats()
        val expectedCount = snapshot.playlistAssets.count { asset ->
            asset.requiresDownload
        }
        val cachedCount = snapshot.playlistAssets.count { asset ->
            !asset.requiresDownload || contentRepository.isAssetCached(asset)
        }

        val body = CacheReportRequest(
            currentPlaylistId = snapshot.playlistInfo?.id,
            currentPlaylistName = snapshot.playlistInfo?.name,
            playlistVersion = snapshot.playlistVersion,
            currentLayoutId = snapshot.layout?.id,
            currentLayoutName = snapshot.layout?.name,
            layoutVersion = snapshot.layoutVersion,
            cacheTotalBytes = cacheStats.totalSizeBytes,
            cacheUsedBytes = cacheStats.totalSizeBytes,
            cachedAssetCount = cachedCount,
            expectedAssetCount = expectedCount.coerceAtLeast(cachedCount),
            pendingDownloadCount = 0,
            syncStatus = "OK",
            lastSuccessfulSyncAt = Instant.now().toString(),
            completedCommandId = completedCommandId,
            commandFailed = if (commandFailed) true else null,
            commandError = commandError,
            assets = cachedAssets.distinctBy { it.assetId }.map { entity ->
                val asset = AssetInfo.fromCache(
                    id = entity.assetId,
                    name = entity.assetName,
                    type = entity.assetType,
                    mimeType = entity.mimeType,
                    durationSeconds = entity.durationSeconds,
                    position = entity.position,
                    downloadUrl = entity.downloadUrl,
                    fileSize = entity.fileSize,
                    url = entity.remoteUrl,
                    assetVersion = entity.fileVersion.toIntOrNull()
                )
                val localFile = contentRepository.findCachedFileForAsset(asset)
                CacheReportAsset(
                    assetId = asset.id,
                    assetName = asset.name,
                    assetType = asset.type,
                    mimeType = asset.mimeType,
                    playlistId = snapshot.playlistInfo?.id,
                    playlistName = snapshot.playlistInfo?.name,
                    fileSize = localFile?.length()?.toInt(),
                    assetVersion = asset.assetVersion,
                    contentHash = asset.contentHash,
                    downloadStatus = if (localFile != null) "CACHED" else "MISSING",
                    localCacheStatus = if (localFile != null) "PRESENT" else "ABSENT",
                    downloadedAt = entity.downloadTimestamp?.let {
                        Instant.ofEpochMilli(it).toString()
                    }
                )
            }
        )

        return try {
            api.reportCache(token = token, body = body)
            securePrefs.lastSuccessfulSyncAt = body.lastSuccessfulSyncAt
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun reportPartialSync(
        syncResponse: SyncResponse,
        pendingDownloadCount: Int,
        error: String? = null
    ): Boolean {
        if (!sessionGuard.isPairedWithToken()) return false
        val token = sessionGuard.requirePairedToken()
        val body = CacheReportRequest(
            currentPlaylistId = syncResponse.playlist?.id,
            currentPlaylistName = syncResponse.playlist?.name,
            playlistVersion = syncResponse.playlistVersion,
            currentLayoutId = syncResponse.layout?.id,
            currentLayoutName = syncResponse.layout?.name,
            layoutVersion = syncResponse.layoutVersion,
            pendingDownloadCount = pendingDownloadCount,
            syncStatus = if (pendingDownloadCount > 0) "PARTIAL" else "FAILED",
            lastFailedSyncAt = if (error != null) Instant.now().toString() else null,
            lastSyncError = error,
            assets = emptyList()
        )
        return try {
            api.reportCache(token = token, body = body)
            true
        } catch (_: Exception) {
            false
        }
    }
}
