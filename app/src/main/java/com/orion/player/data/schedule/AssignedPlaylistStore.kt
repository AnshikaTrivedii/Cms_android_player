package com.orion.player.data.schedule

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.GsonConfig
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.repository.ContentRepository
import com.orion.player.data.sync.PlaybackMode
import com.orion.player.data.sync.PlaybackSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the assigned / manual playlist taken before a scheduled playlist
 * overwrites the single Room cache row. Used to leave an expired schedule even
 * when the CMS is lagging or the network is down. Asset files are not deleted.
 */
@Singleton
class AssignedPlaylistStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentRepository: ContentRepository
) {
    private val gson: Gson = GsonConfig.create()
    private val file: File get() = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun save(snapshot: PlaybackSnapshot) {
        if (snapshot.mode != PlaybackMode.FULL_SCREEN) return
        val playlist = snapshot.playlistInfo ?: return
        if (playlist.id.isBlank()) return
        val assets = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        if (assets.isEmpty()) return
        val dto = AssignedSnapshotDto(
            playlistId = playlist.id,
            playlistName = playlist.name,
            playlistVersion = snapshot.playlistVersion,
            assets = assets,
            localFilePaths = snapshot.localFiles.mapValues { it.value.absolutePath },
            currentIndex = snapshot.currentIndex
        )
        runCatching {
            file.writeText(gson.toJson(dto))
            Log.i(TAG, "Saved assigned playlist fallback playlistId=${playlist.id} assets=${assets.size}")
        }.onFailure {
            Log.w(TAG, "Failed to save assigned playlist fallback", it)
        }
    }

    @Synchronized
    fun load(): PlaybackSnapshot? {
        if (!file.exists()) return null
        val dto = runCatching {
            gson.fromJson(file.readText(), AssignedSnapshotDto::class.java)
        }.getOrNull() ?: return null
        if (dto.playlistId.isBlank() || dto.assets.isEmpty()) return null

        val localFiles = buildMap {
            for (asset in dto.assets) {
                val stored = dto.localFilePaths[asset.id]?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                val resolved = stored ?: contentRepository.findCachedFileForAsset(asset)
                if (resolved != null) put(asset.id, resolved)
            }
        }
        if (dto.assets.none { it.isPlayable(localFiles) }) return null

        return PlaybackSnapshot(
            mode = PlaybackMode.FULL_SCREEN,
            assets = dto.assets,
            localFiles = localFiles,
            playlistInfo = PlaylistInfo.of(dto.playlistId, dto.playlistName),
            playlistVersion = dto.playlistVersion,
            layoutVersion = null,
            layout = null,
            playlistAssets = dto.assets,
            currentIndex = dto.currentIndex.coerceIn(0, (dto.assets.size - 1).coerceAtLeast(0)),
            tickers = emptyList(),
            zones = emptyList(),
            zoneIndices = emptyMap()
        )
    }

    @Synchronized
    fun keepAssetIds(): Set<String> {
        if (!file.exists()) return emptySet()
        val dto = runCatching {
            gson.fromJson(file.readText(), AssignedSnapshotDto::class.java)
        }.getOrNull() ?: return emptySet()
        return dto.assets.map { it.id }.filter { it.isNotBlank() }.toSet()
    }

    @Synchronized
    fun playlistId(): String? {
        if (!file.exists()) return null
        val dto = runCatching {
            gson.fromJson(file.readText(), AssignedSnapshotDto::class.java)
        }.getOrNull() ?: return null
        return dto.playlistId.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private data class AssignedSnapshotDto(
        val playlistId: String,
        val playlistName: String,
        val playlistVersion: Int?,
        val assets: List<AssetInfo>,
        val localFilePaths: Map<String, String>,
        val currentIndex: Int
    )

    companion object {
        private const val TAG = "OrionSchedule"
        private const val FILE_NAME = "assigned_playlist_fallback.json"
    }
}
