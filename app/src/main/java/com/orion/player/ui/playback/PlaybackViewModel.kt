package com.orion.player.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.cache.CacheDownloadLogger
import com.orion.player.data.analytics.PlaybackSession
import com.orion.player.data.analytics.PopLogRecord
import com.orion.player.data.analytics.PopSessionLogger
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.deferPopStartUntilReady
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.data.sync.ContentSyncCoordinator
import com.orion.player.data.sync.PlaybackMode
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

    private var mode: PlaybackMode = PlaybackMode.FULL_SCREEN
    private var assets: List<AssetInfo> = emptyList()
    private var playlistAssets: List<AssetInfo> = emptyList()
    private var localFiles: Map<String, File> = emptyMap()
    private var playlistInfo: PlaylistInfo? = null
    private var playlistVersion: Int? = null
    private var currentTickers: List<TickerDisplayConfig> = emptyList()

    private var activePopSession: PlaybackSession? = null

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
        if (playlistAssets.isEmpty() && assets.isEmpty()) return null
        return PlaybackSnapshot(
            mode = PlaybackMode.FULL_SCREEN,
            assets = assets,
            localFiles = localFiles,
            playlistInfo = playlistInfo,
            playlistVersion = playlistVersion,
            layoutVersion = null,
            layout = null,
            playlistAssets = playlistAssets,
            currentIndex = _currentAssetIndex.value,
            tickers = currentTickers,
            zones = emptyList(),
            zoneIndices = emptyMap()
        )
    }

    private fun startPlayback() {
        viewModelScope.launch {
            SyncDiagnostics.logStartupDevice(securePrefs)

            val cached = contentSyncCoordinator.loadCachedSnapshot()
            if (cached != null && snapshotIsPlayable(cached)) {
                applySnapshot(cached, structureChanged = true)
            } else {
                _uiState.value = PlaybackUiState.Loading
            }

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
                        if (fallback != null && snapshotIsPlayable(fallback)) {
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
                    cancelAllPlaybackJobs()
                    clearPlaybackState()
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
        if (snapshotIsPlayable(snapshot)) {
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
        val previousAssets = playlistAssets
        mode = PlaybackMode.FULL_SCREEN
        assets = snapshot.assets
        playlistAssets = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        localFiles = snapshot.localFiles
        playlistInfo = snapshot.playlistInfo
        playlistVersion = snapshot.playlistVersion
        currentTickers = snapshot.tickers
        _currentAssetIndex.value = snapshot.currentIndex

        cancelAllPlaybackJobs()
        val durationsChanged = previousAssets.isNotEmpty() &&
            playlistAssets.size == previousAssets.size &&
            playlistAssets.zip(previousAssets).any { (current, prior) ->
                current.id == prior.id && current != prior
            }
        emitFullScreen()
        SyncDiagnostics.logPlaybackStart(
            playlistName = playlistInfo?.name.orEmpty(),
            assetName = playlistAssets.getOrNull(_currentAssetIndex.value)?.name.orEmpty(),
            assetIndex = _currentAssetIndex.value,
            total = playlistAssets.size
        )
        if (structureChanged || durationsChanged || advanceJob == null || advanceJob?.isActive != true) {
            scheduleCurrentAsset()
        }
    }

    private fun scheduleCurrentAsset() {
        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            val index = _currentAssetIndex.value
            if (index >= playlistAssets.size) return@launch

            val asset = playlistAssets[index]
            val playlistName = playlistInfo?.name.orEmpty()

            if (!asset.isPlayable(localFiles)) {
                beginPopSession(asset, playlistName)
                finalizePopSession("FAILED")
                advanceToNextAsset()
                return@launch
            }

            beginPopSession(asset, playlistName)
            delay(asset.playbackDurationMs())
            finalizePopSession("VERIFIED")
            advanceToNextAsset()
        }
    }

    private suspend fun beginPopSession(asset: AssetInfo, playlistName: String) {
        activePopSession?.takeIf { !it.finalized }?.let { dangling ->
            finalizePopSession("VERIFIED", dangling)
        }

        val session = PlaybackSession(
            assetId = asset.id,
            assetName = asset.name,
            playlistName = playlistName,
            configuredDurationSeconds = asset.durationSeconds.coerceAtLeast(1),
            slotStartTime = Instant.now()
        )
        if (!asset.deferPopStartUntilReady()) {
            session.contentReadyTime = session.slotStartTime
        }
        activePopSession = session
        PopSessionLogger.logSessionStarted(session)
        emitFullScreen()
    }

    fun onPlaybackStarted(assetName: String) {
        val session = activePopSession ?: return
        if (session.finalized) return
        if (session.assetName != assetName) return
        if (session.contentReadyTime != null) return
        val readyTime = Instant.now()
        session.contentReadyTime = readyTime
        PopSessionLogger.logContentReady(session, readyTime)
    }

    private suspend fun finalizePopSession(
        status: String,
        session: PlaybackSession? = activePopSession
    ) {
        val active = session ?: return
        if (active.finalized) return
        active.finalized = true

        val startTime = active.effectiveStartTime()
        val endTime = active.effectiveEndTime(status)
        val durationSeconds = active.effectiveDurationSeconds(status)

        PopSessionLogger.logSessionEnded(
            session = active,
            startTime = startTime,
            endTime = endTime,
            durationSeconds = durationSeconds,
            status = status
        )

        val record = if (status == "FAILED") {
            PopLogRecord.failed(
                deviceName = deviceDisplayName(),
                playlistName = active.playlistName,
                assetName = active.assetName,
                startTime = startTime,
                endTime = endTime
            )
        } else {
            PopLogRecord.verified(
                deviceName = deviceDisplayName(),
                playlistName = active.playlistName,
                assetName = active.assetName,
                startTime = startTime,
                endTime = endTime,
                durationSeconds = durationSeconds
            )
        }

        if (active === activePopSession) {
            activePopSession = null
        }
        queuePopLog(record)
    }

    private fun handleUnpaired() {
        securePrefs.clearCredentials()
        _isUnpaired.value = true
    }

    private fun deviceDisplayName(): String =
        securePrefs.deviceName?.takeIf { it.isNotBlank() } ?: "Orion Display"

    private fun advanceToNextAsset() {
        if (playlistAssets.isEmpty()) return
        val nextIndex = (_currentAssetIndex.value + 1) % playlistAssets.size
        _currentAssetIndex.value = nextIndex
        scheduleCurrentAsset()
    }

    private fun AssetInfo.playbackDurationMs(): Long =
        durationSeconds.coerceAtLeast(1) * 1000L

    private fun emitFullScreen() {
        val index = _currentAssetIndex.value
        if (index >= playlistAssets.size) return
        val asset = playlistAssets[index]
        if (!networkMonitor.isOnline) {
            CacheDownloadLogger.logOfflinePlayback(
                playlistName = playlistInfo?.name.orEmpty(),
                assetName = asset.name
            )
        }
        _uiState.value = PlaybackUiState.PlayingFullScreen(
            asset = asset,
            localFile = localFiles[asset.id],
            currentIndex = index,
            totalAssets = playlistAssets.size,
            playlistName = playlistInfo?.name.orEmpty(),
            tickers = currentTickers,
            playbackSessionId = activePopSession?.sessionId.orEmpty()
        )
    }

    private fun snapshotIsPlayable(snapshot: PlaybackSnapshot): Boolean {
        val playlist = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        return playlist.any { it.isPlayable(snapshot.localFiles) }
    }

    private fun clearPlaybackState() {
        assets = emptyList()
        playlistAssets = emptyList()
        localFiles = emptyMap()
        playlistInfo = null
        currentTickers = emptyList()
    }

    private fun cancelAllPlaybackJobs() {
        advanceJob?.cancel()
        activePopSession?.takeIf { !it.finalized }?.let { session ->
            viewModelScope.launch {
                finalizePopSession("VERIFIED", session)
            }
        }
    }

    fun onAssetFailed(assetName: String) {
        advanceJob?.cancel()
        viewModelScope.launch {
            activePopSession?.takeIf { it.assetName == assetName && !it.finalized }?.let {
                finalizePopSession("FAILED", it)
            }
            advanceToNextAsset()
        }
    }

    fun onUrlLoadSuccess(assetName: String) = onPlaybackStarted(assetName)

    fun onUrlLoadFailed(assetName: String) {
        viewModelScope.launch {
            activePopSession?.takeIf { it.assetName == assetName && !it.finalized }?.let {
                finalizePopSession("FAILED", it)
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(60_000L)
                try {
                    val currentAsset = playlistAssets.getOrNull(_currentAssetIndex.value)?.name

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
        cancelAllPlaybackJobs()
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
    data class PlayingFullScreen(
        val asset: AssetInfo,
        val localFile: File?,
        val currentIndex: Int,
        val totalAssets: Int,
        val playlistName: String,
        val tickers: List<TickerDisplayConfig> = emptyList(),
        val playbackSessionId: String = ""
    ) : PlaybackUiState()
    data class Error(val message: String) : PlaybackUiState()
}
