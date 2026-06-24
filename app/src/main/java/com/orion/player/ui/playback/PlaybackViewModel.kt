package com.orion.player.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.analytics.PopLogRecord
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.deferPopStartUntilReady
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.data.sync.ContentSyncCoordinator
import com.orion.player.data.sync.PlaybackSnapshot
import com.orion.player.data.sync.PlayerEventStreamClient
import com.orion.player.data.sync.RevisionCheckResult
import com.orion.player.data.sync.SyncConfig
import com.orion.player.data.sync.SyncDiagnostics
import com.orion.player.data.sync.SyncOutcome
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.util.DeviceHealthUtil
import com.orion.player.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import javax.inject.Inject

/**
 * ViewModel managing the playback lifecycle:
 * - Near-real-time content sync (SSE + revision polling + fallback)
 * - Advance through assets on a timer
 * - Send heartbeats every 60s
 * - Queue & flush PoP logs every 5 min
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val contentSyncCoordinator: ContentSyncCoordinator,
    private val playerEventStreamClient: PlayerEventStreamClient,
    private val telemetryRepository: TelemetryRepository,
    private val deviceHealthUtil: DeviceHealthUtil,
    private val networkMonitor: NetworkMonitor,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _currentAssetIndex = MutableStateFlow(0)
    val currentAssetIndex: StateFlow<Int> = _currentAssetIndex.asStateFlow()

    private var assets: List<AssetInfo> = emptyList()
    private var localFiles: Map<String, File> = emptyMap()
    private var playlistInfo: PlaylistInfo? = null
    private var campaignName: String? = null
    private var currentTickers: List<TickerDisplayConfig> = emptyList()

    private var currentPopStartTime: Instant? = null
    private var currentPopAssetName: String? = null
    private var currentPopLogged: Boolean = false

    private var advanceJob: Job? = null
    private var revisionPollJob: Job? = null
    private var fullSyncFallbackJob: Job? = null
    private var heartbeatJob: Job? = null
    private var popFlushJob: Job? = null

    private val _isUnpaired = MutableStateFlow(false)
    val isUnpaired: StateFlow<Boolean> = _isUnpaired.asStateFlow()

    init {
        startPlayback()
        startRealtimeSync()
        startNetworkReconnectSync()
        startHeartbeatLoop()
        startPopFlushLoop()
    }

    private fun currentSnapshot(): PlaybackSnapshot? {
        if (assets.isEmpty()) return null
        return PlaybackSnapshot(
            assets = assets,
            localFiles = localFiles,
            playlistInfo = playlistInfo,
            campaignName = campaignName,
            currentIndex = _currentAssetIndex.value,
            tickers = currentTickers
        )
    }

    private fun startPlayback() {
        viewModelScope.launch {
            SyncDiagnostics.logStartupDevice(securePrefs)

            // Case 2: previously synced — start immediately from local cache
            val cached = contentSyncCoordinator.loadCachedSnapshot()
            if (cached != null && cached.assets.any { it.isPlayable(cached.localFiles) }) {
                applySnapshot(cached, structureChanged = true)
            } else {
                _uiState.value = PlaybackUiState.Loading
            }

            // Attempt network sync (updates cache when online)
            when (val outcome = contentSyncCoordinator.syncContent(
                current = currentSnapshot(),
                force = true,
                onDownloadProgress = if (currentSnapshot() == null) {
                    { current, total ->
                        _uiState.value = PlaybackUiState.Downloading(current, total)
                    }
                } else {
                    null
                }
            )) {
                is SyncOutcome.Unpaired -> handleUnpaired()
                is SyncOutcome.NoContent -> {
                    if (currentSnapshot() == null) {
                        _uiState.value = PlaybackUiState.NoContent
                    }
                }
                is SyncOutcome.Failed -> {
                    if (currentSnapshot() == null) {
                        val reason = outcome.message.ifBlank { "Content download failed" }
                        SyncDiagnostics.logWaitingScreen(reason, "startPlayback.Failed")
                        _uiState.value = PlaybackUiState.WaitingForInitialDownload(reason)
                    }
                }
                is SyncOutcome.Updated -> {
                    applySnapshotIfPlayable(outcome.snapshot, outcome.structureChanged)
                    telemetryRepository.flushAll()
                }
                is SyncOutcome.Unchanged -> {
                    if (currentSnapshot() == null) {
                        val fallback = contentSyncCoordinator.loadCachedSnapshot()
                        if (fallback != null && fallback.assets.any { it.isPlayable(fallback.localFiles) }) {
                            applySnapshot(fallback, structureChanged = true)
                        } else {
                            val reason = "Sync unchanged and no playable local cache"
                            SyncDiagnostics.logWaitingScreen(reason, "startPlayback.Unchanged")
                            _uiState.value = PlaybackUiState.WaitingForInitialDownload(reason)
                        }
                    }
                }
                is SyncOutcome.Downloading -> Unit
            }
        }
    }

    /** Resume sync + telemetry flush when network returns. */
    private fun startNetworkReconnectSync() {
        viewModelScope.launch {
            networkMonitor.observeOnline().collect { online ->
                if (online) {
                    telemetryRepository.flushAll()
                    requestContentSync(force = true, reason = "network.reconnected")
                }
            }
        }
    }

    /**
     * Three-tier near-real-time sync:
     * 1. SSE push (instant) via /player/events
     * 2. Lightweight revision poll every 5s via /player/sync-revision
     * 3. Full sync fallback every 15s when revision endpoint unavailable
     */
    private fun startRealtimeSync() {
        playerEventStreamClient.start(viewModelScope)

        viewModelScope.launch {
            playerEventStreamClient.syncTriggers.collect { trigger ->
                trigger.revision?.let { contentSyncCoordinator.onPushRevision(it) }
                requestContentSync(force = true, reason = trigger.reason)
            }
        }

        revisionPollJob?.cancel()
        revisionPollJob = viewModelScope.launch {
            while (true) {
                delay(SyncConfig.REVISION_POLL_INTERVAL_MS)
                when (val result = contentSyncCoordinator.checkRevisionChanged()) {
                    RevisionCheckResult.Changed -> requestContentSync(force = true, reason = "revision.changed")
                    RevisionCheckResult.Unpaired -> handleUnpaired()
                    RevisionCheckResult.Unavailable,
                    RevisionCheckResult.Unchanged -> Unit
                }
            }
        }

        fullSyncFallbackJob?.cancel()
        fullSyncFallbackJob = viewModelScope.launch {
            while (true) {
                val interval = if (contentSyncCoordinator.useAggressiveFullSyncFallback) {
                    SyncConfig.FULL_SYNC_POLL_INTERVAL_MS
                } else {
                    SyncConfig.FULL_SYNC_POLL_INTERVAL_MS * 4
                }
                delay(interval)
                if (contentSyncCoordinator.useAggressiveFullSyncFallback) {
                    requestContentSync(reason = "full.poll.fallback")
                }
            }
        }
    }

    private fun requestContentSync(force: Boolean = false, reason: String = "manual") {
        viewModelScope.launch {
            val current = currentSnapshot()
            when (val outcome = contentSyncCoordinator.syncContent(
                current = current,
                force = force,
                onDownloadProgress = if (current == null) {
                    { completed, total ->
                        _uiState.value = PlaybackUiState.Downloading(completed, total)
                    }
                } else {
                    null
                }
            )) {
                is SyncOutcome.Unpaired -> handleUnpaired()
                is SyncOutcome.NoContent -> {
                    advanceJob?.cancel()
                    assets = emptyList()
                    localFiles = emptyMap()
                    playlistInfo = null
                    _uiState.value = PlaybackUiState.NoContent
                }
                is SyncOutcome.Updated -> {
                    applySnapshotIfPlayable(outcome.snapshot, outcome.structureChanged)
                    telemetryRepository.flushAll()
                }
                is SyncOutcome.Failed -> {
                    if (!outcome.keepPlaying && currentSnapshot() == null) {
                        val reason = outcome.message.ifBlank { "Content download failed" }
                        SyncDiagnostics.logWaitingScreen(reason, "requestContentSync.Failed")
                        _uiState.value = PlaybackUiState.WaitingForInitialDownload(reason)
                    }
                }
                is SyncOutcome.Unchanged, is SyncOutcome.Downloading -> Unit
            }
        }
    }

    private fun applySnapshotIfPlayable(snapshot: PlaybackSnapshot, structureChanged: Boolean) {
        if (snapshot.assets.any { it.isPlayable(snapshot.localFiles) }) {
            applySnapshot(snapshot, structureChanged)
        } else {
            SyncDiagnostics.logPlayabilityCheck(snapshot.assets, snapshot.localFiles)
            SyncDiagnostics.logWaitingScreen(
                "Snapshot has no playable local files",
                "applySnapshotIfPlayable"
            )
            _uiState.value = PlaybackUiState.WaitingForInitialDownload(
                "Downloaded content is not playable yet"
            )
        }
    }

    private fun applySnapshot(snapshot: PlaybackSnapshot, structureChanged: Boolean) {
        val previousAssets = assets
        assets = snapshot.assets
        localFiles = snapshot.localFiles
        playlistInfo = snapshot.playlistInfo
        campaignName = snapshot.campaignName
        currentTickers = snapshot.tickers
        _currentAssetIndex.value = snapshot.currentIndex

        val durationsChanged = previousAssets.isNotEmpty() &&
            snapshot.assets.size == previousAssets.size &&
            snapshot.assets.zip(previousAssets).any { (current, previous) ->
                current.id == previous.id && current != previous
            }

        emitCurrentAsset()
        SyncDiagnostics.logPlaybackStart(
            playlistName = playlistInfo?.name.orEmpty(),
            assetName = assets.getOrNull(_currentAssetIndex.value)?.name.orEmpty(),
            assetIndex = _currentAssetIndex.value,
            total = assets.size
        )
        if (structureChanged || durationsChanged || advanceJob == null || advanceJob?.isActive != true) {
            scheduleCurrentAsset()
        }
    }

    private fun scheduleCurrentAsset() {
        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            val index = _currentAssetIndex.value
            if (index >= assets.size) return@launch

            val asset = assets[index]
            if (!asset.isPlayable(localFiles)) {
                completePopTracking(asset.name, "FAILED")
                advanceToNextAsset()
                return@launch
            }

            preparePopTracking(asset)

            delay(asset.playbackDurationMs())

            if (!currentPopLogged) {
                completePopTracking(asset.name, "VERIFIED")
            }

            advanceToNextAsset()
        }
    }

    private fun preparePopTracking(asset: AssetInfo) {
        currentPopAssetName = asset.name
        currentPopLogged = false
        currentPopStartTime = if (asset.deferPopStartUntilReady()) null else Instant.now()
    }

    /** Called when video playback or web content is actually ready. */
    fun onPlaybackStarted(assetName: String) {
        if (currentPopAssetName == assetName && !currentPopLogged) {
            currentPopStartTime = Instant.now()
        }
    }

    private suspend fun completePopTracking(assetName: String, status: String) {
        if (currentPopLogged) return

        val startTime = currentPopStartTime ?: Instant.now()
        val endTime = Instant.now()

        val record = if (status == "FAILED") {
            PopLogRecord.failed(
                deviceName = deviceDisplayName(),
                playlistName = playlistInfo?.name.orEmpty(),
                campaignName = campaignName.orEmpty(),
                assetName = assetName,
                startTime = startTime,
                endTime = endTime
            )
        } else {
            PopLogRecord.verified(
                deviceName = deviceDisplayName(),
                playlistName = playlistInfo?.name.orEmpty(),
                campaignName = campaignName.orEmpty(),
                assetName = assetName,
                startTime = startTime,
                endTime = endTime
            )
        }

        currentPopLogged = true
        queuePopLog(record)
    }

    private fun handleUnpaired() {
        securePrefs.clearCredentials()
        _isUnpaired.value = true
    }

    private fun deviceDisplayName(): String =
        securePrefs.deviceName?.takeIf { it.isNotBlank() } ?: "Orion Display"

    private fun advanceToNextAsset() {
        if (assets.isEmpty()) return
        val nextIndex = (_currentAssetIndex.value + 1) % assets.size
        _currentAssetIndex.value = nextIndex
        emitCurrentAsset()
        scheduleCurrentAsset()
    }

    private fun AssetInfo.playbackDurationMs(): Long =
        durationSeconds.coerceAtLeast(1) * 1000L

    private fun emitCurrentAsset() {
        val index = _currentAssetIndex.value
        if (index < assets.size) {
            val asset = assets[index]
            val file = localFiles[asset.id]
            _uiState.value = PlaybackUiState.Playing(
                asset = asset,
                localFile = file,
                currentIndex = index,
                totalAssets = assets.size,
                playlistName = playlistInfo?.name ?: "",
                tickers = currentTickers
            )
        }
    }

    fun onAssetFailed(assetName: String) {
        viewModelScope.launch {
            completePopTracking(assetName, "FAILED")
        }
        advanceJob?.cancel()
        advanceToNextAsset()
    }

    fun onUrlLoadSuccess(assetName: String) {
        onPlaybackStarted(assetName)
    }

    fun onUrlLoadFailed(assetName: String) {
        viewModelScope.launch {
            completePopTracking(assetName, "FAILED")
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(60_000L)
                try {
                    val currentAsset = if (_currentAssetIndex.value < assets.size) {
                        assets[_currentAssetIndex.value].name
                    } else null

                    val response = telemetryRepository.sendHeartbeat(
                        cpu = deviceHealthUtil.getCpuUsage().coerceAtLeast(0),
                        ram = deviceHealthUtil.getRamUsage().coerceAtLeast(0),
                        temp = deviceHealthUtil.getTemperature().coerceAtLeast(0),
                        currentContent = currentAsset
                    )

                    if (response?.syncRequired == true) {
                        requestContentSync(force = true, reason = "heartbeat.syncRequired")
                    } else if (contentSyncCoordinator.consumeRevisionIfChanged(response?.contentRevision)) {
                        requestContentSync(force = true, reason = "heartbeat.revision")
                    }

                    telemetryRepository.flushAll()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startPopFlushLoop() {
        popFlushJob?.cancel()
        popFlushJob = viewModelScope.launch {
            while (true) {
                delay(300_000L)
                try {
                    telemetryRepository.flushAll()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun queuePopLog(record: PopLogRecord) {
        try {
            telemetryRepository.queuePopLog(record)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun retry() {
        startPlayback()
    }

    override fun onCleared() {
        super.onCleared()
        playerEventStreamClient.stop()
        advanceJob?.cancel()
        revisionPollJob?.cancel()
        fullSyncFallbackJob?.cancel()
        heartbeatJob?.cancel()
        popFlushJob?.cancel()
    }
}

sealed class PlaybackUiState {
    data object Loading : PlaybackUiState()
    data class Downloading(val current: Int, val total: Int) : PlaybackUiState()
    data class WaitingForInitialDownload(val reason: String = "No local content available") : PlaybackUiState()
    data object NoContent : PlaybackUiState()
    data class Playing(
        val asset: AssetInfo,
        val localFile: File?,
        val currentIndex: Int,
        val totalAssets: Int,
        val playlistName: String,
        val tickers: List<TickerDisplayConfig> = emptyList()
    ) : PlaybackUiState()
    data class Error(val message: String) : PlaybackUiState()
}
