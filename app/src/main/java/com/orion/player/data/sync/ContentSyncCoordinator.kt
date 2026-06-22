package com.orion.player.data.sync

import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.remote.SyncResponse
import com.orion.player.data.repository.ContentRepository
import com.orion.player.data.repository.PlaylistCacheRepository
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.resolveActiveTicker
import retrofit2.HttpException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackSnapshot(
    val assets: List<AssetInfo>,
    val localFiles: Map<String, File>,
    val playlistInfo: PlaylistInfo?,
    val campaignName: String?,
    val currentIndex: Int,
    val ticker: TickerDisplayConfig? = null
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

/**
 * Central coordinator for offline-first content sync.
 */
@Singleton
class ContentSyncCoordinator @Inject constructor(
    private val contentRepository: ContentRepository,
    private val playlistCacheRepository: PlaylistCacheRepository,
    private val securePrefs: SecurePrefs
) {
    private var lastKnownRevision: String? = null
    private var lastSyncAttemptMs: Long = 0L
    private var syncInProgress: Boolean = false

    var revisionEndpointAvailable: Boolean? = null
        private set

    val useAggressiveFullSyncFallback: Boolean
        get() = revisionEndpointAvailable == false

    suspend fun loadCachedSnapshot(): PlaybackSnapshot? =
        playlistCacheRepository.loadSnapshot()

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
    ): SyncOutcome {
        if (securePrefs.getBearerToken() == null) return SyncOutcome.Unpaired

        val now = System.currentTimeMillis()
        if (!force && syncInProgress) return SyncOutcome.Unchanged
        if (!force && now - lastSyncAttemptMs < SyncConfig.MIN_SYNC_DEBOUNCE_MS) {
            return SyncOutcome.Unchanged
        }

        syncInProgress = true
        lastSyncAttemptMs = now

        return try {
            val syncResponse = contentRepository.syncPlaylist()
                ?: return SyncOutcome.Unpaired

            updateRevisionFromSync(syncResponse)

            if (syncResponse.playlist == null || syncResponse.assets.isEmpty()) {
                return SyncOutcome.NoContent
            }

            val newAssets = syncResponse.assets.sortedBy { it.position }
            val resolvedTicker = syncResponse.tickers.resolveActiveTicker()
            val playlistChanged = syncResponse.playlist != current?.playlistInfo
            val assetsChanged = newAssets != current?.assets
            val tickerChanged = resolvedTicker != current?.ticker

            if (!force && current != null && !assetsChanged && !playlistChanged && !tickerChanged) {
                return SyncOutcome.Unchanged
            }

            if (!assetsChanged && !playlistChanged && current != null) {
                val snapshot = current.copy(
                    ticker = resolvedTicker,
                    campaignName = syncResponse.campaignName ?: current.campaignName
                )
                playlistCacheRepository.saveSnapshot(
                    snapshot = snapshot,
                    contentRevision = lastKnownRevision
                )
                return SyncOutcome.Updated(snapshot = snapshot, structureChanged = false)
            }

            onDownloadProgress?.invoke(0, newAssets.size)
            val newFiles = contentRepository.downloadAllAssets(newAssets) { completed, total ->
                onDownloadProgress?.invoke(completed, total)
            }

            contentRepository.cleanupStaleCache(
                newAssets.map { contentRepository.getCacheKey(it) }.toSet()
            )

            val mergedFiles = buildLocalFileMap(newAssets, newFiles, current)

            if (!newAssets.any { it.isPlayable(mergedFiles) }) {
                return fallbackOrFail(
                    current = current,
                    message = "Failed to download content"
                )
            }

            val structureChanged = current == null ||
                newAssets.map { it.id } != current.assets.map { it.id } ||
                playlistChanged

            val nextIndex = when {
                current == null -> 0
                structureChanged -> 0
                else -> current.currentIndex.coerceIn(0, (newAssets.size - 1).coerceAtLeast(0))
            }

            val snapshot = PlaybackSnapshot(
                assets = newAssets,
                localFiles = mergedFiles,
                playlistInfo = syncResponse.playlist,
                campaignName = syncResponse.campaignName,
                currentIndex = nextIndex,
                ticker = resolvedTicker
            )

            playlistCacheRepository.saveSnapshot(
                snapshot = snapshot,
                contentRevision = lastKnownRevision
            )

            SyncOutcome.Updated(snapshot = snapshot, structureChanged = structureChanged)
        } catch (e: HttpException) {
            if (e.code() == 401) SyncOutcome.Unpaired
            else fallbackOrFail(current = current, message = e.message ?: "Sync failed")
        } catch (e: Exception) {
            fallbackOrFail(current = current, message = e.message ?: "Sync failed")
        } finally {
            syncInProgress = false
        }
    }

    private suspend fun fallbackOrFail(
        current: PlaybackSnapshot?,
        message: String
    ): SyncOutcome {
        if (current != null && current.assets.any { it.isPlayable(current.localFiles) }) {
            return SyncOutcome.Failed(message = message, keepPlaying = true)
        }

        val cached = playlistCacheRepository.loadSnapshot()
        if (cached != null && cached.assets.any { it.isPlayable(cached.localFiles) }) {
            return SyncOutcome.Updated(
                snapshot = cached,
                structureChanged = current == null,
                fromCache = true
            )
        }

        return SyncOutcome.Failed(message = message, keepPlaying = false)
    }

    private fun buildLocalFileMap(
        assets: List<AssetInfo>,
        downloaded: Map<String, File>,
        current: PlaybackSnapshot?
    ): Map<String, File> = buildMap {
        for (asset in assets) {
            val file = downloaded[asset.id]
                ?: current?.localFiles?.get(asset.id)
                ?: contentRepository.getLocalFile(asset).takeIf {
                    contentRepository.isAssetCached(asset)
                }
            if (file != null && file.exists()) {
                put(asset.id, file)
            }
        }
    }

    private suspend fun updateRevisionFromSync(syncResponse: SyncResponse) {
        if (revisionEndpointAvailable == false) return
        try {
            val revision = contentRepository.getSyncRevision().revision
            lastKnownRevision = revision
            revisionEndpointAvailable = true
        } catch (_: HttpException) {
            // Keep playing with manifest-only sync.
        } catch (_: Exception) {
            // Ignore revision refresh errors during full sync.
        }
    }
}
