package com.orion.player.data.repository

import com.google.gson.Gson
import com.orion.player.data.local.CachedAssetEntity
import com.orion.player.data.local.CachedPlaylistEntity
import com.orion.player.data.local.CachedTickerEntity
import com.orion.player.data.local.PlaylistCacheDao
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.GsonConfig
import com.orion.player.data.remote.LayoutInfo
import com.orion.player.data.remote.ZoneType
import com.orion.player.data.remote.toZoneSnapshots
import com.orion.player.data.remote.collectLayoutAssets
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.sync.PlaybackMode
import com.orion.player.data.sync.PlaybackSnapshot
import com.orion.player.data.sync.PlayerPlaybackConfig
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerHeightPercent
import com.orion.player.data.ticker.TickerPosition
import com.orion.player.data.ticker.TickerPriority
import com.orion.player.data.ticker.TickerScope
import com.orion.player.data.ticker.TickerSpeed
import com.orion.player.data.ticker.TickerStyle
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SyncVersions(
    val playlistVersion: Int?,
    val layoutVersion: Int?
)

data class CachedPlaylistMetadata(
    val playlistId: String?,
    val playlistName: String?,
    val playlistVersion: Int?,
    val lastSyncTime: Long?,
    val playbackMode: String
)

/**
 * Persists and restores playlist manifests + asset metadata for offline-first playback.
 */
@Singleton
class PlaylistCacheRepository @Inject constructor(
    private val playlistCacheDao: PlaylistCacheDao,
    private val contentRepository: ContentRepository
) {
    private val gson: Gson = GsonConfig.create()

    suspend fun hasCachedContent(): Boolean {
        val cached = playlistCacheDao.getPlaylist() ?: return false
        if (!PlayerPlaybackConfig.LAYOUT_PLAYBACK_ENABLED &&
            cached.playbackMode == PlaybackMode.LAYOUT.name
        ) {
            return false
        }
        val assets = playlistCacheDao.getAssets()
        if (assets.isEmpty()) return false
        if (cached.playbackMode == PlaybackMode.LAYOUT.name) {
            if (cached.layoutJson.isNullOrBlank()) return false
            val layout = runCatching {
                gson.fromJson(cached.layoutJson, LayoutInfo::class.java)
            }.getOrNull() ?: return false
            val assetInfos = playlistCacheDao.getAssets().map { it.toAssetInfo() }
            val localFiles = buildLocalFilesFromCache(playlistCacheDao.getAssets())
            val mergedAssets = layout.collectLayoutAssets(assetInfos)
            val zones = layout.toZoneSnapshots(mergedAssets)
            return layoutContentIsCached(zones, localFiles)
        }
        return assets.any { entity ->
            val asset = entity.toAssetInfo()
            contentRepository.findCachedFileForAsset(asset)?.exists() == true
        }
    }

    suspend fun getSyncVersions(): SyncVersions {
        val cached = playlistCacheDao.getPlaylist()
        return SyncVersions(
            playlistVersion = cached?.playlistVersion,
            layoutVersion = null
        )
    }

    suspend fun getPlaylistVersion(): Int? = playlistCacheDao.getPlaylist()?.playlistVersion

    suspend fun getLayoutVersion(): Int? = null

    suspend fun saveSnapshot(
        snapshot: PlaybackSnapshot,
        contentRevision: String? = null
    ) {
        if (snapshot.mode == PlaybackMode.LAYOUT && !PlayerPlaybackConfig.LAYOUT_PLAYBACK_ENABLED) {
            return
        }
        val now = System.currentTimeMillis()
        val entity = when (snapshot.mode) {
            PlaybackMode.FULL_SCREEN -> {
                val playlist = snapshot.playlistInfo ?: return
                CachedPlaylistEntity(
                    playbackMode = PlaybackMode.FULL_SCREEN.name,
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    playlistVersion = snapshot.playlistVersion,
                    layoutVersion = null,
                    layoutJson = null,
                    contentRevision = contentRevision,
                    lastSyncTime = now
                )
            }
            PlaybackMode.LAYOUT -> {
                val layout = snapshot.layout ?: return
                CachedPlaylistEntity(
                    playbackMode = PlaybackMode.LAYOUT.name,
                    playlistId = null,
                    playlistName = layout.name,
                    playlistVersion = null,
                    layoutVersion = snapshot.layoutVersion,
                    layoutJson = gson.toJson(layout),
                    contentRevision = contentRevision,
                    lastSyncTime = now
                )
            }
        }

        playlistCacheDao.replaceAll(
            playlist = entity,
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
                    fileVersion = asset.assetVersion?.toString() ?: contentRepository.getCacheKey(asset),
                    downloadTimestamp = if (localFile?.exists() == true) {
                        localFile.lastModified()
                    } else {
                        0L
                    }
                )
            }
        )
        if (snapshot.mode == PlaybackMode.FULL_SCREEN) {
            saveTickers(snapshot.tickers)
        } else {
            playlistCacheDao.replaceTickers(emptyList())
        }
    }

    private suspend fun saveTickers(tickers: List<TickerDisplayConfig>) {
        playlistCacheDao.replaceTickers(
            tickers.mapIndexed { index, ticker ->
                CachedTickerEntity(
                    tickerId = ticker.id,
                    text = ticker.text,
                    scope = ticker.scope.name,
                    position = ticker.position.name,
                    speed = ticker.speed.name,
                    priority = ticker.priority.name,
                    heightPercent = ticker.heightPercent,
                    style = ticker.style.name,
                    backgroundColor = ticker.backgroundColorHex,
                    textColor = ticker.textColorHex,
                    sortOrder = index
                )
            }
        )
    }

    private suspend fun loadTickers(): List<TickerDisplayConfig> {
        return playlistCacheDao.getTickers().map { cached ->
            TickerDisplayConfig(
                id = cached.tickerId,
                text = cached.text,
                scope = TickerScope.from(cached.scope),
                position = TickerPosition.from(cached.position),
                speed = TickerSpeed.from(cached.speed),
                priority = TickerPriority.from(cached.priority),
                heightPercent = TickerHeightPercent.clamp(cached.heightPercent),
                style = TickerStyle.from(cached.style),
                backgroundColorHex = cached.backgroundColor,
                textColorHex = cached.textColor
            )
        }
    }

    suspend fun loadSnapshot(): PlaybackSnapshot? {
        val cachedPlaylist = playlistCacheDao.getPlaylist() ?: return null
        if (!PlayerPlaybackConfig.LAYOUT_PLAYBACK_ENABLED &&
            cachedPlaylist.playbackMode == PlaybackMode.LAYOUT.name
        ) {
            return null
        }
        val cachedAssets = playlistCacheDao.getAssets()
        if (cachedAssets.isEmpty()) return null

        val assets = cachedAssets.map { it.toAssetInfo() }
        val localFiles = buildLocalFilesFromCache(cachedAssets)

        return when (cachedPlaylist.playbackMode) {
            PlaybackMode.LAYOUT.name -> {
                val layout = cachedPlaylist.layoutJson?.let {
                    runCatching { gson.fromJson(it, LayoutInfo::class.java) }.getOrNull()
                } ?: return null
                val mergedAssets = layout.collectLayoutAssets(assets)
                val zones = layout.toZoneSnapshots(mergedAssets).map { zone ->
                    zone.copy(currentIndex = 0)
                }
                PlaybackSnapshot(
                    mode = PlaybackMode.LAYOUT,
                    assets = mergedAssets,
                    localFiles = localFiles,
                    playlistInfo = null,
                    playlistVersion = null,
                    layoutVersion = cachedPlaylist.layoutVersion,
                    layout = layout,
                    playlistAssets = emptyList(),
                    currentIndex = 0,
                    tickers = emptyList(),
                    zones = zones,
                    zoneIndices = zones.associate { it.zone.id to it.currentIndex }
                )
            }
            else -> {
                if (localFiles.isEmpty()) return null
                val playlistId = cachedPlaylist.playlistId ?: return null
                val playlistName = cachedPlaylist.playlistName ?: return null
                PlaybackSnapshot(
                    mode = PlaybackMode.FULL_SCREEN,
                    assets = assets,
                    localFiles = localFiles,
                    playlistInfo = PlaylistInfo.of(playlistId, playlistName),
                    playlistVersion = cachedPlaylist.playlistVersion,
                    layoutVersion = null,
                    layout = null,
                    playlistAssets = assets,
                    currentIndex = 0,
                    tickers = loadTickers(),
                    zones = emptyList(),
                    zoneIndices = emptyMap()
                )
            }
        }
    }

    suspend fun getCachedAssetRowCount(): Int = playlistCacheDao.getAssets().size

    suspend fun hasRoomPlaylist(): Boolean = playlistCacheDao.getPlaylist() != null

    suspend fun getLastSyncTime(): Long? =
        playlistCacheDao.getPlaylist()?.lastSyncTime

    suspend fun getContentRevision(): String? =
        playlistCacheDao.getPlaylist()?.contentRevision

    suspend fun getCachedAssetEntities(): List<CachedAssetEntity> =
        playlistCacheDao.getAssets()

    suspend fun getPlaylistMetadata(): CachedPlaylistMetadata? {
        val cached = playlistCacheDao.getPlaylist() ?: return null
        return CachedPlaylistMetadata(
            playlistId = cached.playlistId,
            playlistName = cached.playlistName,
            playlistVersion = cached.playlistVersion,
            lastSyncTime = cached.lastSyncTime,
            playbackMode = cached.playbackMode
        )
    }

    private fun buildLocalFilesFromCache(cachedAssets: List<CachedAssetEntity>): Map<String, File> =
        buildMap {
            for (entity in cachedAssets) {
                val asset = entity.toAssetInfo()
                val storedPath = entity.localFilePath?.let { File(it) }?.takeIf { it.exists() }
                val file = storedPath ?: contentRepository.findCachedFileForAsset(asset)
                if (file != null && file.exists() && file.length() > 0L) {
                    put(entity.assetId, file)
                }
            }
        }

    private fun layoutContentIsCached(
        zones: List<com.orion.player.data.remote.ZoneSnapshot>,
        localFiles: Map<String, File>
    ): Boolean {
        val contentZones = zones.filter { zone ->
            zone.zone.type == ZoneType.PLAYLIST || zone.zone.type == ZoneType.IMAGE
        }
        if (contentZones.isEmpty()) {
            return zones.any { it.zone.type == ZoneType.TICKER && !it.ticker?.text.isNullOrBlank() }
        }
        return contentZones.all { zone ->
            when (zone.zone.type) {
                ZoneType.PLAYLIST ->
                    zone.assets.isNotEmpty() && zone.assets.any { asset ->
                        localFiles[asset.id]?.exists() == true
                    }
                ZoneType.IMAGE ->
                    zone.assets.isNotEmpty() &&
                        zone.assets.firstOrNull()?.let { asset ->
                            localFiles[asset.id]?.exists() == true
                        } == true
                else -> true
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
}
