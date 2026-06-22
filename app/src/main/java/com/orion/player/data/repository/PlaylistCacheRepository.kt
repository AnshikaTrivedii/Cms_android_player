package com.orion.player.data.repository

import com.orion.player.data.local.CachedAssetEntity
import com.orion.player.data.local.CachedPlaylistEntity
import com.orion.player.data.local.CachedTickerEntity
import com.orion.player.data.local.PlaylistCacheDao
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.sync.PlaybackSnapshot
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerPosition
import com.orion.player.data.ticker.TickerSpeed
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and restores playlist manifests + asset metadata for offline-first playback.
 */
@Singleton
class PlaylistCacheRepository @Inject constructor(
    private val playlistCacheDao: PlaylistCacheDao,
    private val contentRepository: ContentRepository
) {

    suspend fun hasCachedContent(): Boolean {
        val playlist = playlistCacheDao.getPlaylist() ?: return false
        val assets = playlistCacheDao.getAssets()
        if (assets.isEmpty()) return false
        return assets.any { entity ->
            entity.localFilePath?.let { File(it).exists() } == true
        }
    }

    suspend fun saveSnapshot(
        snapshot: PlaybackSnapshot,
        contentRevision: String? = null
    ) {
        val playlist = snapshot.playlistInfo ?: return
        val now = System.currentTimeMillis()

        playlistCacheDao.replaceAll(
            playlist = CachedPlaylistEntity(
                playlistId = playlist.id,
                playlistName = playlist.name,
                campaignName = snapshot.campaignName,
                contentRevision = contentRevision,
                lastSyncTime = now
            ),
            assets = snapshot.assets.map { asset ->
                val localFile = snapshot.localFiles[asset.id]
                CachedAssetEntity(
                    assetId = asset.id,
                    assetName = asset.name,
                    assetType = asset.type,
                    mimeType = asset.mimeType,
                    durationSeconds = asset.durationSeconds,
                    position = asset.position,
                    downloadUrl = asset.downloadUrl,
                    fileSize = asset.fileSize,
                    remoteUrl = asset.url,
                    localFilePath = localFile?.absolutePath,
                    fileVersion = contentRepository.getCacheKey(asset),
                    downloadTimestamp = if (localFile?.exists() == true) {
                        localFile.lastModified()
                    } else {
                        0L
                    }
                )
            }
        )
        saveTicker(snapshot.ticker)
    }

    private suspend fun saveTicker(ticker: TickerDisplayConfig?) {
        if (ticker == null) {
            playlistCacheDao.clearTicker()
            return
        }

        playlistCacheDao.upsertTicker(
            CachedTickerEntity(
                tickerId = ticker.id,
                text = ticker.text,
                position = ticker.position.name,
                speed = ticker.speed.name,
                backgroundColor = ticker.backgroundColorHex,
                textColor = ticker.textColorHex
            )
        )
    }

    private suspend fun loadTicker(): TickerDisplayConfig? {
        val cached = playlistCacheDao.getTicker() ?: return null
        val text = cached.text ?: return null
        val tickerId = cached.tickerId ?: return null
        if (text.isBlank()) return null

        return TickerDisplayConfig(
            id = tickerId,
            text = text,
            position = TickerPosition.from(cached.position.orEmpty()),
            speed = TickerSpeed.from(cached.speed.orEmpty()),
            backgroundColorHex = cached.backgroundColor.orEmpty().ifBlank { "#000000" },
            textColorHex = cached.textColor.orEmpty().ifBlank { "#FFFFFF" }
        )
    }

    suspend fun loadSnapshot(): PlaybackSnapshot? {
        val cachedPlaylist = playlistCacheDao.getPlaylist() ?: return null
        val cachedAssets = playlistCacheDao.getAssets()
        if (cachedAssets.isEmpty()) return null

        val assets = cachedAssets.map { it.toAssetInfo() }
        val localFiles = buildMap {
            for (entity in cachedAssets) {
                val path = entity.localFilePath ?: continue
                val file = File(path)
                if (file.exists()) {
                    put(entity.assetId, file)
                }
            }
        }

        if (localFiles.isEmpty()) return null

        return PlaybackSnapshot(
            assets = assets,
            localFiles = localFiles,
            playlistInfo = PlaylistInfo(
                id = cachedPlaylist.playlistId,
                name = cachedPlaylist.playlistName
            ),
            campaignName = cachedPlaylist.campaignName,
            currentIndex = 0,
            ticker = loadTicker()
        )
    }

    suspend fun getLastSyncTime(): Long? =
        playlistCacheDao.getPlaylist()?.lastSyncTime

    suspend fun getContentRevision(): String? =
        playlistCacheDao.getPlaylist()?.contentRevision

    private fun CachedAssetEntity.toAssetInfo() = AssetInfo(
        id = assetId,
        name = assetName,
        type = assetType,
        mimeType = mimeType,
        durationSeconds = durationSeconds,
        position = position,
        downloadUrl = downloadUrl,
        fileSize = fileSize,
        url = remoteUrl
    )
}
