package com.orion.player.data.sync

import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.playlistManifestChangedFrom
import com.orion.player.data.remote.PlaylistInfo

/**
 * Compares locally cached playlist state with a fresh server manifest.
 */
data class PlaylistDiff(
    val localVersion: Int?,
    val remoteVersion: Int?,
    val versionChanged: Boolean,
    val playlistRenamed: Boolean,
    val assetsAdded: List<String>,
    val assetsRemoved: List<String>,
    val orderChanged: Boolean,
    val metadataChanged: Boolean,
    val requiresManifestRefresh: Boolean,
    val requiresQueueRebuild: Boolean,
    val requiresDownload: Boolean
) {
    val hasChanges: Boolean
        get() = versionChanged ||
            playlistRenamed ||
            assetsAdded.isNotEmpty() ||
            assetsRemoved.isNotEmpty() ||
            orderChanged ||
            metadataChanged
}

object PlaylistChangeDetector {

    fun diff(
        localVersion: Int?,
        remoteVersion: Int?,
        localPlaylist: PlaylistInfo?,
        remotePlaylist: PlaylistInfo?,
        localAssets: List<AssetInfo>,
        remoteAssets: List<AssetInfo>,
        serverRemovedAssetIds: Set<String> = emptySet()
    ): PlaylistDiff {
        val localIds = localAssets.map { it.id }
        val remoteIds = remoteAssets.map { it.id }
        val localIdSet = localIds.toSet()
        val remoteIdSet = remoteIds.toSet()

        val added = remoteIds.filter { it !in localIdSet }
        val removed = (localIds.filter { it !in remoteIdSet } + serverRemovedAssetIds.filter { it !in remoteIdSet })
            .distinct()
        val orderChanged = localIds.isNotEmpty() && remoteIds.isNotEmpty() && localIds != remoteIds
        val metadataChanged = remoteAssets.isNotEmpty() &&
            localAssets.isNotEmpty() &&
            remoteAssets.playlistManifestChangedFrom(localAssets)
        val versionChanged = remoteVersion != null &&
            localVersion != null &&
            remoteVersion != localVersion
        val versionAdvanced = remoteVersion != null &&
            (localVersion == null || remoteVersion > localVersion)
        val playlistRenamed = localPlaylist != null &&
            remotePlaylist != null &&
            localPlaylist.id == remotePlaylist.id &&
            localPlaylist.name != remotePlaylist.name

        val requiresDownload = remoteAssets.map { it.id }.toSet().any { assetId ->
            val remote = remoteAssets.first { it.id == assetId }
            val local = localAssets.firstOrNull { it.id == assetId }
            when {
                local == null -> true
                remote.assetVersion != null &&
                    local.assetVersion != null &&
                    remote.assetVersion != local.assetVersion -> true
                remote.fileSize > 0 && local.fileSize > 0 && remote.fileSize != local.fileSize -> true
                else -> false
            }
        } || added.isNotEmpty() || removed.isNotEmpty()

        val requiresQueueRebuild = orderChanged ||
            metadataChanged ||
            added.isNotEmpty() ||
            removed.isNotEmpty() ||
            playlistRenamed ||
            versionChanged ||
            versionAdvanced

        val requiresManifestRefresh = versionAdvanced ||
            serverRemovedAssetIds.isNotEmpty() ||
            (remoteAssets.isEmpty() && localAssets.isNotEmpty()) ||
            (remoteAssets.isNotEmpty() && (added.isNotEmpty() || removed.isNotEmpty() || orderChanged || metadataChanged))

        return PlaylistDiff(
            localVersion = localVersion,
            remoteVersion = remoteVersion,
            versionChanged = versionChanged || versionAdvanced,
            playlistRenamed = playlistRenamed,
            assetsAdded = added,
            assetsRemoved = removed,
            orderChanged = orderChanged,
            metadataChanged = metadataChanged,
            requiresManifestRefresh = requiresManifestRefresh,
            requiresQueueRebuild = requiresQueueRebuild,
            requiresDownload = requiresDownload
        )
    }
}
