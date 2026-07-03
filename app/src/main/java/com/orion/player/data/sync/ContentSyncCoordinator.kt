package com.orion.player.data.sync

import com.orion.player.data.playback.PlaylistManifestLogger
import com.orion.player.data.playback.inPlaylistOrder
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.hasSyncContentChangedFrom
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.AssetType.remoteSourceUrl
import com.orion.player.data.remote.AssetType.requiresDownload as assetTypeRequiresDownload
import com.orion.player.data.remote.LayoutInfo
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.remote.SyncResponse
import com.orion.player.data.remote.ZoneSnapshot
import com.orion.player.data.remote.ZoneType
import com.orion.player.data.remote.toZoneSnapshots
import com.orion.player.data.remote.collectLayoutAssets
import com.orion.player.data.enterprise.DeviceLogCollector
import com.orion.player.data.enterprise.RemoteCommandExecutor
import com.orion.player.data.repository.ContentRepository
import com.orion.player.data.repository.ContentCacheRepository
import com.orion.player.data.repository.PlaylistCacheRepository
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerLogger
import com.orion.player.data.ticker.resolveActiveTickers
import com.orion.player.util.NetworkDiagnostics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class PlaybackMode {
    FULL_SCREEN,
    LAYOUT
}

data class PlaybackSnapshot(
    val mode: PlaybackMode,
    val assets: List<AssetInfo>,
    val localFiles: Map<String, File>,
    val playlistInfo: PlaylistInfo?,
    val playlistVersion: Int?,
    val layoutVersion: Int?,
    val layout: LayoutInfo?,
    val playlistAssets: List<AssetInfo>,
    val currentIndex: Int,
    val tickers: List<TickerDisplayConfig> = emptyList(),
    val zones: List<ZoneSnapshot> = emptyList(),
    val zoneIndices: Map<String, Int> = emptyMap()
)

sealed class SyncOutcome {
    data object Unpaired : SyncOutcome()
    data object Unchanged : SyncOutcome()
    data object NoContent : SyncOutcome()
    data class Downloading(val current: Int, val total: Int) : SyncOutcome()
    data class Updated(
        val snapshot: PlaybackSnapshot,
        val structureChanged: Boolean,
        val fromCache: Boolean = false
    ) : SyncOutcome()
    data class Failed(val message: String, val keepPlaying: Boolean) : SyncOutcome()
}

sealed class RevisionCheckResult {
    data object Unchanged : RevisionCheckResult()
    data object Changed : RevisionCheckResult()
    data object Unavailable : RevisionCheckResult()
    data object Unpaired : RevisionCheckResult()
}

@Singleton
class ContentSyncCoordinator @Inject constructor(
    private val contentRepository: ContentRepository,
    private val playlistCacheRepository: PlaylistCacheRepository,
    private val contentCacheRepository: ContentCacheRepository,
    private val securePrefs: SecurePrefs,
    private val remoteCommandExecutor: RemoteCommandExecutor,
    private val deviceLogCollector: DeviceLogCollector
) {
    companion object {
        private const val TAG = "OrionSync"
    }

    private var lastKnownRevision: String? = null
    private var lastSyncAttemptMs: Long = 0L
    private val syncMutex = Mutex()

    var revisionEndpointAvailable: Boolean? = null
        private set

    val useAggressiveFullSyncFallback: Boolean
        get() = revisionEndpointAvailable == false

    suspend fun loadCachedSnapshot(): PlaybackSnapshot? {
        val snapshot = playlistCacheRepository.loadSnapshot()
        if (snapshot != null) {
            contentCacheRepository.validateCurrentPlaylistCache()
        }
        contentCacheRepository.logCacheSummary()
        SyncDiagnostics.logCacheLoad(
            snapshot = snapshot,
            roomAssetCount = playlistCacheRepository.getCachedAssetRowCount(),
            roomPlaylistPresent = playlistCacheRepository.hasRoomPlaylist(),
            diskFileCount = contentRepository.countCacheFilesOnDisk()
        )
        return snapshot
    }

    suspend fun hasCachedContent(): Boolean =
        playlistCacheRepository.hasCachedContent()

    suspend fun checkRevisionChanged(): RevisionCheckResult {
        if (securePrefs.getBearerToken() == null) return RevisionCheckResult.Unpaired

        return try {
            val response = contentRepository.getSyncRevision()
            revisionEndpointAvailable = true

            if (lastKnownRevision == null) {
                lastKnownRevision = response.revision
                    ?: playlistCacheRepository.getContentRevision()
                return RevisionCheckResult.Unchanged
            }

            if (response.revision != lastKnownRevision) {
                lastKnownRevision = response.revision
                RevisionCheckResult.Changed
            } else {
                RevisionCheckResult.Unchanged
            }
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> RevisionCheckResult.Unpaired
                404 -> {
                    revisionEndpointAvailable = false
                    RevisionCheckResult.Unavailable
                }
                else -> RevisionCheckResult.Unchanged
            }
        } catch (e: Exception) {
            RevisionCheckResult.Unchanged
        }
    }

    fun onPushRevision(revision: String?) {
        if (!revision.isNullOrBlank()) {
            lastKnownRevision = revision
        }
    }

    fun consumeRevisionIfChanged(revision: String?): Boolean {
        if (revision.isNullOrBlank()) return false
        if (revision == lastKnownRevision) return false
        lastKnownRevision = revision
        return true
    }

    suspend fun syncContent(
        current: PlaybackSnapshot?,
        force: Boolean = false,
        onDownloadProgress: ((Int, Int) -> Unit)? = null
    ): SyncOutcome = syncMutex.withLock {
        if (securePrefs.getBearerToken() == null) return SyncOutcome.Unpaired

        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAttemptMs < SyncConfig.MIN_SYNC_DEBOUNCE_MS) {
            return SyncOutcome.Unchanged
        }

        lastSyncAttemptMs = now

        try {
            val versions = playlistCacheRepository.getSyncVersions()
            val cachedSnapshot = current ?: playlistCacheRepository.loadSnapshot()
            val (knownIds, assetVersions) = if (force) {
                null to null
            } else {
                buildIncrementalSyncHints(cachedSnapshot)
            }
            var syncResponse = contentRepository.syncPlaylist(
                playlistVersion = versions.playlistVersion,
                layoutVersion = null,
                knownAssetIds = knownIds,
                assetVersions = assetVersions
            ) ?: return SyncOutcome.Unpaired

            if (syncResponse.isLayoutMode && !PlayerPlaybackConfig.LAYOUT_PLAYBACK_ENABLED) {
                Log.i(TAG, "Ignoring layout payload from sync (layout playback disabled)")
            }

            SyncDiagnostics.logSyncResponse(securePrefs, syncResponse)
            updateRevisionFromSync(syncResponse)

            PlaylistRefreshLogger.logVersionCheck(
                localVersion = versions.playlistVersion,
                remoteVersion = syncResponse.playlistVersion
            )

            if (syncResponse.unchanged) {
                val refreshOutcome = handleUnchangedSyncResponse(
                    syncResponse = syncResponse,
                    current = cachedSnapshot,
                    localVersion = versions.playlistVersion,
                    force = force,
                    onDownloadProgress = onDownloadProgress
                )
                if (refreshOutcome != null) return refreshOutcome
                return SyncOutcome.Unchanged
            }

            return dispatchSyncResponse(syncResponse, cachedSnapshot, force, onDownloadProgress)
        } catch (e: HttpException) {
            if (e.code() == 401) SyncOutcome.Unpaired
            else {
                val message = NetworkDiagnostics.userMessage("GET /player/sync", e)
                SyncDiagnostics.logSyncFailed(message, e)
                fallbackOrFail(current = current, message = message)
            }
        } catch (e: Exception) {
            val message = NetworkDiagnostics.userMessage("GET /player/sync", e)
            SyncDiagnostics.logSyncFailed(message, e)
            fallbackOrFail(current = current, message = message)
        }
    }

    private suspend fun handleUnchangedSyncResponse(
        syncResponse: SyncResponse,
        current: PlaybackSnapshot?,
        localVersion: Int?,
        force: Boolean,
        onDownloadProgress: ((Int, Int) -> Unit)?
    ): SyncOutcome? {
        var snapshot = current?.let { applyUnchangedSyncResponse(it, syncResponse) } ?: current
        val localAssets = snapshot?.playlistAssets.orEmpty()
        val partialAssets = syncResponse.resolvedAssets()
        val cachedRevision = playlistCacheRepository.getContentRevision()

        val partialDiff = if (partialAssets.isNotEmpty()) {
            PlaylistChangeDetector.diff(
                localVersion = localVersion,
                remoteVersion = syncResponse.playlistVersion,
                localPlaylist = snapshot?.playlistInfo,
                remotePlaylist = syncResponse.playlist,
                localAssets = localAssets,
                remoteAssets = partialAssets,
                serverRemovedAssetIds = syncResponse.resolvedRemovedAssetIds()
            )
        } else {
            null
        }

        val versionMismatch = syncResponse.playlistVersion != null &&
            localVersion != null &&
            syncResponse.playlistVersion != localVersion
        val revisionMismatch = lastKnownRevision != null &&
            cachedRevision != null &&
            lastKnownRevision != cachedRevision
        val missingLocalFiles = snapshot != null && snapshotNeedsLocalFiles(snapshot)
        val needsFullManifest = versionMismatch ||
            revisionMismatch ||
            syncResponse.resolvedRemovedAssetIds().isNotEmpty() ||
            partialAssets.isEmpty() ||
            partialDiff?.hasChanges == true ||
            missingLocalFiles

        if (needsFullManifest) {
            val reason = when {
                versionMismatch -> "playlistVersion mismatch"
                revisionMismatch -> "content revision mismatch"
                syncResponse.resolvedRemovedAssetIds().isNotEmpty() -> "removedAssetIds reported"
                partialDiff?.hasChanges == true -> "partial manifest differs from cache"
                missingLocalFiles -> "missing local files"
                else -> "empty assets on unchanged response"
            }
            PlaylistRefreshLogger.logManifestRefetch(reason)
            val fullResponse = fetchFullManifest() ?: return null
            SyncDiagnostics.logSyncResponse(securePrefs, fullResponse)

            if (!fullResponse.unchanged) {
                return dispatchSyncResponse(fullResponse, snapshot, force = true, onDownloadProgress)
            }

            val fullAssets = fullResponse.resolvedAssets().inPlaylistOrder()
            if (fullAssets.isNotEmpty() && snapshot != null) {
                val fullDiff = PlaylistChangeDetector.diff(
                    localVersion = localVersion,
                    remoteVersion = fullResponse.playlistVersion,
                    localPlaylist = snapshot.playlistInfo,
                    remotePlaylist = fullResponse.playlist,
                    localAssets = localAssets,
                    remoteAssets = fullAssets,
                    serverRemovedAssetIds = fullResponse.resolvedRemovedAssetIds()
                )
                PlaylistRefreshLogger.logDiff(fullDiff)
                if (fullDiff.hasChanges) {
                    return dispatchSyncResponse(fullResponse, snapshot, force = true, onDownloadProgress)
                }
            }
        }

        if (snapshot != null &&
            partialAssets.isNotEmpty() &&
            partialDiff?.hasChanges == true
        ) {
            PlaylistRefreshLogger.logDiff(partialDiff)
            return dispatchSyncResponse(
                syncResponse.withUpdatedManifest(partialAssets),
                snapshot,
                force = true,
                onDownloadProgress
            )
        }

        if (snapshot != null && missingLocalFiles) {
            Log.w(
                TAG,
                "Server returned unchanged with empty assets[] but local files are missing — requesting full manifest"
            )
            val fullResponse = fetchFullManifest() ?: return null
            SyncDiagnostics.logSyncResponse(securePrefs, fullResponse)
            if (!fullResponse.unchanged) {
                return dispatchSyncResponse(fullResponse, snapshot, force = true, onDownloadProgress)
            }
        }

        snapshot?.let { repairMissingLocalFiles(it) }?.let { repaired ->
            playlistCacheRepository.saveSnapshot(repaired, lastKnownRevision)
            SyncDiagnostics.logOutcome("Updated", "repaired missing files after unchanged sync")
            return SyncOutcome.Updated(repaired, structureChanged = false)
        }

        PlaylistRefreshLogger.logUnchangedAccepted(localVersion)
        return null
    }

    private suspend fun fetchFullManifest(): SyncResponse? =
        contentRepository.syncPlaylist(
            playlistVersion = null,
            layoutVersion = null,
            knownAssetIds = null,
            assetVersions = null
        )

    private suspend fun dispatchSyncResponse(
        syncResponse: SyncResponse,
        current: PlaybackSnapshot?,
        force: Boolean,
        onDownloadProgress: ((Int, Int) -> Unit)?
    ): SyncOutcome {
        syncResponse.commands?.let { remoteCommandExecutor.dispatch(it) }
        if (syncResponse.isLayoutMode && !PlayerPlaybackConfig.LAYOUT_PLAYBACK_ENABLED) {
            if (syncResponse.playlist != null && syncResponse.resolvedAssets().isNotEmpty()) {
                return syncFullScreenMode(syncResponse, current, force, onDownloadProgress)
            }
            SyncDiagnostics.logSyncNoContent()
            return SyncOutcome.NoContent
        }
        return if (syncResponse.isLayoutMode) {
            syncLayoutMode(syncResponse, current, force, onDownloadProgress)
        } else {
            syncFullScreenMode(syncResponse, current, force, onDownloadProgress)
        }
    }

    /**
     * Only tell the server about assets we have verified on disk.
     * Reporting Room metadata without local files makes the backend skip the manifest
     * and omit presigned download URLs (requiresDownload=false, downloadUrl=null).
     */
    private fun buildIncrementalSyncHints(
        snapshot: PlaybackSnapshot?
    ): Pair<List<String>?, Map<String, Int>?> {
        if (snapshot == null) return null to null
        val confirmed = locallyCachedAssets(snapshot)
        if (confirmed.isEmpty()) return null to null
        val ids = confirmed.map { it.id }
        val versions = confirmed.mapNotNull { asset ->
            asset.assetVersion?.let { asset.id to it }
        }.toMap()
        return ids to versions.takeIf { it.isNotEmpty() }
    }

    private fun locallyCachedAssets(snapshot: PlaybackSnapshot): List<AssetInfo> {
        val allAssets = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        return allAssets.filter { it.isPlayable(snapshot.localFiles) }
    }

    private fun snapshotNeedsLocalFiles(snapshot: PlaybackSnapshot): Boolean {
        val required = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        return required.any { asset ->
            asset.assetTypeRequiresDownload() && !asset.isPlayable(snapshot.localFiles)
        }
    }

    private fun applyUnchangedSyncResponse(
        snapshot: PlaybackSnapshot,
        response: SyncResponse
    ): PlaybackSnapshot {
        if (!PlayerPlaybackConfig.LAYOUT_PLAYBACK_ENABLED) return snapshot
        if (!response.isLayoutMode || response.layout == null) return snapshot
        val layout = response.layout
        val mergedAssets = layout.collectLayoutAssets(snapshot.assets)
        val zones = layout.toZoneSnapshots(mergedAssets).map { zoneSnap ->
            val priorIndex = snapshot.zoneIndices[zoneSnap.zone.id] ?: zoneSnap.currentIndex
            zoneSnap.copy(
                currentIndex = priorIndex.coerceIn(0, (zoneSnap.assets.size - 1).coerceAtLeast(0))
            )
        }
        return snapshot.copy(
            mode = PlaybackMode.LAYOUT,
            layout = layout,
            layoutVersion = response.layoutVersion,
            assets = mergedAssets,
            zones = zones,
            zoneIndices = zones.associate { it.zone.id to it.currentIndex }
        )
    }

    private suspend fun syncFullScreenMode(
        syncResponse: SyncResponse,
        current: PlaybackSnapshot?,
        force: Boolean,
        onDownloadProgress: ((Int, Int) -> Unit)?
    ): SyncOutcome {
        if (syncResponse.playlist == null || syncResponse.resolvedAssets().isEmpty()) {
            SyncDiagnostics.logSyncNoContent()
            return SyncOutcome.NoContent
        }

        val newAssets = syncResponse.resolvedAssets().inPlaylistOrder()
        PlaylistManifestLogger.logManifest(
            source = "sync",
            playlistId = syncResponse.playlist?.id,
            playlistName = syncResponse.playlist?.name,
            playlistVersion = syncResponse.playlistVersion,
            assets = newAssets
        )
        TickerLogger.received(syncResponse.resolvedTickers())
        val resolvedTickers = syncResponse.resolvedTickers().resolveActiveTickers()
        if (resolvedTickers.isEmpty()) TickerLogger.cleared() else TickerLogger.resolved(resolvedTickers)

        val localAssets = current?.playlistAssets.orEmpty()
        val diff = PlaylistChangeDetector.diff(
            localVersion = current?.playlistVersion,
            remoteVersion = syncResponse.playlistVersion,
            localPlaylist = current?.playlistInfo,
            remotePlaylist = syncResponse.playlist,
            localAssets = localAssets,
            remoteAssets = newAssets,
            serverRemovedAssetIds = syncResponse.resolvedRemovedAssetIds()
        )
        PlaylistRefreshLogger.logDiff(diff)

        val playlistChanged = syncResponse.playlist?.id != current?.playlistInfo?.id ||
            syncResponse.playlist?.name != current?.playlistInfo?.name ||
            current?.mode != PlaybackMode.FULL_SCREEN
        val assetsChanged = newAssets.hasSyncContentChangedFrom(localAssets)
        val tickerChanged = resolvedTickers != current?.tickers
        val versionChanged = diff.versionChanged

        if (!force && current != null && !assetsChanged && !playlistChanged && !tickerChanged && !versionChanged) {
            return SyncOutcome.Unchanged
        }

        if (!assetsChanged && !playlistChanged && !versionChanged && !diff.requiresDownload && current != null) {
            val snapshot = current.copy(
                assets = newAssets,
                playlistAssets = newAssets,
                tickers = resolvedTickers,
                playlistInfo = syncResponse.playlist ?: current.playlistInfo,
                playlistVersion = syncResponse.playlistVersion
            )
            playlistCacheRepository.saveSnapshot(snapshot, lastKnownRevision)
            PlaylistRefreshLogger.logCacheUpdated(
                playlistName = snapshot.playlistInfo?.name.orEmpty(),
                version = snapshot.playlistVersion
            )
            PlaylistRefreshLogger.logQueueRebuilt(
                playlistName = snapshot.playlistInfo?.name.orEmpty(),
                assetCount = newAssets.size,
                resetIndex = diff.orderChanged,
                newVersion = snapshot.playlistVersion
            )
            return SyncOutcome.Updated(
                snapshot = snapshot,
                structureChanged = diff.orderChanged || diff.assetsAdded.isNotEmpty() || diff.assetsRemoved.isNotEmpty()
            )
        }

        return finalizeSync(
            syncResponse = syncResponse,
            current = current,
            newAssets = newAssets,
            snapshotBuilder = { mergedFiles, nextIndex ->
                PlaybackSnapshot(
                    mode = PlaybackMode.FULL_SCREEN,
                    assets = newAssets,
                    localFiles = mergedFiles,
                    playlistInfo = syncResponse.playlist,
                    playlistVersion = syncResponse.playlistVersion,
                    layoutVersion = null,
                    layout = null,
                    playlistAssets = newAssets,
                    currentIndex = nextIndex,
                    tickers = resolvedTickers
                )
            },
            structureChanged = current == null ||
                current.mode != PlaybackMode.FULL_SCREEN ||
                diff.orderChanged ||
                diff.assetsAdded.isNotEmpty() ||
                diff.assetsRemoved.isNotEmpty() ||
                playlistChanged,
            onDownloadProgress = onDownloadProgress
        )
    }

    private fun assetNeedsDownload(
        asset: AssetInfo,
        current: PlaybackSnapshot?,
        localFiles: Map<String, File>
    ): Boolean {
        if (!asset.assetTypeRequiresDownload()) return false
        if (!asset.isPlayable(localFiles)) return true
        val prior = current?.playlistAssets?.find { it.id == asset.id } ?: return false
        if (asset.assetVersion != null &&
            prior.assetVersion != null &&
            asset.assetVersion != prior.assetVersion
        ) {
            return true
        }
        if (asset.fileSize > 0 && prior.fileSize > 0 && asset.fileSize != prior.fileSize) {
            return true
        }
        if (asset.contentHash != null &&
            prior.contentHash != null &&
            asset.contentHash != prior.contentHash
        ) {
            return true
        }
        return false
    }

    private suspend fun syncLayoutMode(
        syncResponse: SyncResponse,
        current: PlaybackSnapshot?,
        force: Boolean,
        onDownloadProgress: ((Int, Int) -> Unit)?
    ): SyncOutcome {
        val layout = syncResponse.layout
        if (layout == null || layout.zones.isEmpty()) {
            SyncDiagnostics.logSyncNoContent()
            return SyncOutcome.NoContent
        }

        val newAssets = layout.collectLayoutAssets(syncResponse.resolvedAssets())
        SyncDiagnostics.logLayoutZones(layout, newAssets)
        val zones = layout.toZoneSnapshots(newAssets).map { zoneSnap ->
            val priorIndex = current?.zoneIndices?.get(zoneSnap.zone.id) ?: 0
            zoneSnap.copy(
                currentIndex = priorIndex.coerceIn(0, (zoneSnap.assets.size - 1).coerceAtLeast(0))
            )
        }

        val layoutChanged = layout.id != current?.layout?.id ||
            syncResponse.layoutVersion != current.layoutVersion ||
            current?.mode != PlaybackMode.LAYOUT ||
            zones.map { it.zone.id to it.zone.type } != current.zones.map { it.zone.id to it.zone.type }
        val assetsChanged = newAssets.hasSyncContentChangedFrom(
            current?.layout?.collectLayoutAssets(current.assets).orEmpty()
        )

        if (!force && current != null && !assetsChanged && !layoutChanged) {
            repairMissingLocalFiles(current)?.let { repaired ->
                playlistCacheRepository.saveSnapshot(repaired, lastKnownRevision)
                return SyncOutcome.Updated(repaired, structureChanged = false)
            }
            if (layoutHasPlayableContent(zones, current.localFiles)) {
                return SyncOutcome.Unchanged
            }
        }

        return finalizeSync(
            syncResponse = syncResponse,
            current = current,
            newAssets = newAssets,
            snapshotBuilder = { mergedFiles, _ ->
                PlaybackSnapshot(
                    mode = PlaybackMode.LAYOUT,
                    assets = newAssets,
                    localFiles = mergedFiles,
                    playlistInfo = null,
                    playlistVersion = null,
                    layoutVersion = syncResponse.layoutVersion,
                    layout = layout,
                    playlistAssets = emptyList(),
                    currentIndex = 0,
                    tickers = emptyList(),
                    zones = zones,
                    zoneIndices = zones.associate { it.zone.id to it.currentIndex }
                )
            },
            structureChanged = layoutChanged || current == null,
            onDownloadProgress = onDownloadProgress,
            requirePlayable = { files -> layoutHasPlayableContent(zones, files) }
        )
    }

    private suspend fun finalizeSync(
        syncResponse: SyncResponse,
        current: PlaybackSnapshot?,
        newAssets: List<AssetInfo>,
        snapshotBuilder: (Map<String, File>, Int) -> PlaybackSnapshot,
        structureChanged: Boolean,
        onDownloadProgress: ((Int, Int) -> Unit)?,
        requirePlayable: (Map<String, File>) -> Boolean = { files ->
            newAssets.any { it.isPlayable(files) }
        }
    ): SyncOutcome {
        val toDownload = newAssets.filter { asset ->
            assetNeedsDownload(asset, current, mergedFilesBeforeDownload(current, newAssets))
        }
        onDownloadProgress?.invoke(0, toDownload.size.coerceAtLeast(1))
        val newFiles = contentRepository.downloadAllAssets(toDownload) { completed, total ->
            contentCacheRepository.setDownloadProgress(completed, total)
            onDownloadProgress?.invoke(completed, total)
        }
        contentCacheRepository.clearDownloadProgress()

        val mergedFiles = buildLocalFileMap(newAssets, newFiles, current)
        SyncDiagnostics.logPlayabilityCheck(newAssets, mergedFiles)

        if (!requirePlayable(mergedFiles)) {
            val failedDetails = newAssets
                .filterNot { it.isPlayable(mergedFiles) }
                .joinToString { "${it.name}(${it.type})" }
            Log.e(TAG, "No playable content after sync. Failed: $failedDetails")
            return fallbackOrFail(
                current = current,
                message = "Failed to download content: $failedDetails"
            )
        }

        val keepKeys = syncResponse.resolvedCurrentAssetIds()
            .ifEmpty { newAssets.map { it.id }.toSet() }
        contentRepository.cleanupStaleCache(keepKeys)

        val nextIndex = when {
            current == null -> 0
            structureChanged -> 0
            else -> current.currentIndex.coerceIn(0, (newAssets.size - 1).coerceAtLeast(0))
        }

        val snapshot = snapshotBuilder(mergedFiles, nextIndex)
        playlistCacheRepository.saveSnapshot(snapshot, lastKnownRevision)
        contentCacheRepository.logCacheSummary()
        PlaylistRefreshLogger.logCacheUpdated(
            playlistName = snapshot.playlistInfo?.name.orEmpty(),
            version = snapshot.playlistVersion
        )
        PlaylistRefreshLogger.logQueueRebuilt(
            playlistName = snapshot.playlistInfo?.name.orEmpty(),
            assetCount = newAssets.size,
            resetIndex = structureChanged,
            newVersion = snapshot.playlistVersion
        )
        SyncDiagnostics.logOutcome("Updated", "playable=${mergedFiles.size} assets mode=${snapshot.mode}")
        return SyncOutcome.Updated(snapshot = snapshot, structureChanged = structureChanged)
    }

    private fun layoutHasPlayableContent(
        zones: List<ZoneSnapshot>,
        localFiles: Map<String, File>
    ): Boolean {
        val contentZones = zones.filter {
            it.zone.type == ZoneType.PLAYLIST || it.zone.type == ZoneType.IMAGE
        }
        if (contentZones.isEmpty()) {
            return zones.any { it.zone.type == ZoneType.TICKER && !it.ticker?.text.isNullOrBlank() }
        }
        return contentZones.all { zone ->
            when (zone.zone.type) {
                ZoneType.PLAYLIST ->
                    zone.assets.isNotEmpty() && zone.assets.any { it.isPlayable(localFiles) }
                ZoneType.IMAGE ->
                    zone.assets.isNotEmpty() &&
                        zone.assets.firstOrNull()?.isPlayable(localFiles) == true
                else -> true
            }
        }
    }

    private suspend fun repairMissingLocalFiles(snapshot: PlaybackSnapshot): PlaybackSnapshot? {
        val requiredAssets = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        if (requiredAssets.isEmpty()) return null

        val missing = requiredAssets.filter { asset ->
            asset.assetTypeRequiresDownload() && !asset.isPlayable(snapshot.localFiles)
        }
        if (missing.isEmpty()) return null

        val withUrls = missing.filter { !it.remoteSourceUrl().isNullOrBlank() }
        if (withUrls.isEmpty()) {
            Log.e(
                TAG,
                "Cannot repair ${missing.size} assets — server omitted download URLs. " +
                    "Missing: ${missing.joinToString { it.name }}"
            )
            return null
        }

        Log.i(TAG, "Repairing ${withUrls.size} missing local files: ${withUrls.joinToString { it.name }}")
        val downloaded = contentRepository.downloadAllAssets(withUrls)
        val mergedFiles = buildLocalFileMap(requiredAssets, downloaded, snapshot)
        if (mergedFiles == snapshot.localFiles) return null

        return snapshot.copy(
            assets = requiredAssets,
            localFiles = mergedFiles,
            mode = PlaybackMode.FULL_SCREEN,
            layout = null,
            layoutVersion = null,
            zones = emptyList(),
            zoneIndices = emptyMap()
        )
    }

    private suspend fun fallbackOrFail(
        current: PlaybackSnapshot?,
        message: String
    ): SyncOutcome {
        if (current != null && snapshotIsPlayable(current)) {
            return SyncOutcome.Failed(message = message, keepPlaying = true)
        }

        val cached = playlistCacheRepository.loadSnapshot()
        if (cached != null && snapshotIsPlayable(cached)) {
            return SyncOutcome.Updated(
                snapshot = cached,
                structureChanged = current == null,
                fromCache = true
            )
        }

        return SyncOutcome.Failed(message = message, keepPlaying = false)
    }

    private fun snapshotIsPlayable(snapshot: PlaybackSnapshot): Boolean =
        snapshot.playlistAssets.any { it.isPlayable(snapshot.localFiles) }

    private fun mergedFilesBeforeDownload(
        current: PlaybackSnapshot?,
        assets: List<AssetInfo>
    ): Map<String, File> = buildMap {
        for (asset in assets) {
            val file = current?.localFiles?.get(asset.id)
                ?: contentRepository.findCachedFileForAsset(asset)?.takeIf {
                    it.exists() && it.length() > 0L
                }
            if (file != null) put(asset.id, file)
        }
    }

    private fun buildLocalFileMap(
        assets: List<AssetInfo>,
        downloaded: Map<String, File>,
        current: PlaybackSnapshot?
    ): Map<String, File> = buildMap {
        for (asset in assets) {
            val file = downloaded[asset.id]
                ?: current?.localFiles?.get(asset.id)
                ?: contentRepository.findCachedFileForAsset(asset)?.takeIf {
                    it.exists() && it.length() > 0L
                }
            if (file != null && file.exists() && file.length() > 0L) {
                put(asset.id, file)
            }
        }
    }

    private suspend fun updateRevisionFromSync(@Suppress("UNUSED_PARAMETER") syncResponse: SyncResponse) {
        if (revisionEndpointAvailable == false) return
        try {
            val revision = contentRepository.getSyncRevision().revision
            lastKnownRevision = revision
            revisionEndpointAvailable = true
        } catch (_: HttpException) {
        } catch (_: Exception) {
        }
    }
}
