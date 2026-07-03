package com.orion.player.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.analytics.PlaybackSession
import com.orion.player.data.analytics.PopLogRecord
import com.orion.player.data.analytics.PopSessionLogger
import com.orion.player.data.cache.CacheDownloadLogger
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.OrionRecoveryLogger
import com.orion.player.data.recovery.PlaybackRecoveryCoordinator
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.playback.PlaybackEngineLogger
import com.orion.player.data.playback.PlaybackSlotLogger
import com.orion.player.data.playback.PlaylistManifestLogger
import com.orion.player.data.playback.inPlaylistOrder
import com.orion.player.data.playback.hasExplicitDuration
import com.orion.player.data.playback.playbackSlotDurationMs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.deferPopStartUntilReady
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.data.remote.AssetType.playlistManifestChangedFrom
import com.orion.player.data.enterprise.DeviceHealthReporter
import com.orion.player.data.enterprise.DeviceLogCollector
import com.orion.player.data.enterprise.DevicePermissionReporter
import com.orion.player.data.enterprise.RemoteCommandExecutor
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.data.stability.StabilityMonitor
import com.orion.player.data.sync.ContentSyncCoordinator
import com.orion.player.data.sync.PlaybackMode
import com.orion.player.data.sync.PlaybackSnapshot
import com.orion.player.data.sync.PlaylistRefreshLogger
import com.orion.player.data.sync.PlayerEventStreamClient
import com.orion.player.data.sync.RevisionCheckResult
import com.orion.player.data.sync.SyncConfig
import com.orion.player.data.sync.SyncDiagnostics
import com.orion.player.data.sync.SyncOutcome
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.util.DeviceHealthUtil
import com.orion.player.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val contentSyncCoordinator: ContentSyncCoordinator,
    private val playerEventStreamClient: PlayerEventStreamClient,
    private val telemetryRepository: TelemetryRepository,
    private val deviceHealthUtil: DeviceHealthUtil,
    private val networkMonitor: NetworkMonitor,
    private val securePrefs: SecurePrefs,
    private val healthMonitor: PlayerHealthMonitor,
    private val recoveryCoordinator: PlaybackRecoveryCoordinator,
    private val stabilityMonitor: StabilityMonitor,
    private val deviceHealthReporter: DeviceHealthReporter,
    private val devicePermissionReporter: DevicePermissionReporter,
    private val remoteCommandExecutor: RemoteCommandExecutor,
    private val deviceLogCollector: DeviceLogCollector
) : ViewModel() {

    companion object {
        private const val CONTENT_READY_TIMEOUT_MS = 30_000L
        private const val VIDEO_END_TIMEOUT_MS = 86_400_000L
    }

    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _currentAssetIndex = MutableStateFlow(0)
    val currentAssetIndex: StateFlow<Int> = _currentAssetIndex.asStateFlow()

    private var mode: PlaybackMode = PlaybackMode.FULL_SCREEN
    private var assets: List<AssetInfo> = emptyList()
    private var playlistAssets: List<AssetInfo> = emptyList()
    private var localFiles: Map<String, File> = emptyMap()
    private var playlistInfo: com.orion.player.data.remote.PlaylistInfo? = null
    private var playlistVersion: Int? = null
    private var currentTickers: List<TickerDisplayConfig> = emptyList()

    private var activePopSession: PlaybackSession? = null
    private var pendingSnapshot: PlaybackSnapshot? = null

    private var advanceJob: Job? = null
    private var slotGeneration = 0
    private var contentReadyDeferred: CompletableDeferred<Unit>? = null
    private var videoEndedDeferred: CompletableDeferred<Unit>? = null

    private var videoStopToken = 0L

    private var revisionPollJob: Job? = null
    private var fullSyncFallbackJob: Job? = null
    private var heartbeatJob: Job? = null
    private var popFlushJob: Job? = null
    private var backgroundServicesStarted = false

    private val _isUnpaired = MutableStateFlow(false)
    val isUnpaired: StateFlow<Boolean> = _isUnpaired.asStateFlow()

    init {
        healthMonitor.recordStartupInit()
        healthMonitor.registerSlotLoopAliveChecker { advanceJob?.isActive == true }
        recoveryCoordinator.registerPlaybackRestartHandler { reason ->
            recoverPlayback(reason)
        }
        remoteCommandExecutor.registerForceSyncHandler {
            requestContentSync(force = true, reason = "remote.force_sync")
        }
        healthMonitor.recordPlaybackExpected(securePrefs.isAuthenticated())
        startPlayback()
    }

    fun bindScreenshotWindow(windowProvider: () -> android.view.Window?) {
        remoteCommandExecutor.registerScreenshotWindowProvider(windowProvider)
    }

    fun unbindScreenshotWindow() {
        remoteCommandExecutor.unregisterScreenshotWindowProvider()
    }

    private fun ensureBackgroundServicesStarted() {
        if (backgroundServicesStarted) return
        backgroundServicesStarted = true
        OrionRecoveryLogger.logBackgroundSyncStarted("post-playback")
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
            healthMonitor.recordPlaybackExpected(securePrefs.isAuthenticated())

            val cached = contentSyncCoordinator.loadCachedSnapshot()
            if (cached != null && snapshotIsPlayable(cached)) {
                OrionRecoveryLogger.logCachedPlaylistLoaded(
                    assetCount = cached.playlistAssets.size,
                    playlistName = cached.playlistInfo?.name.orEmpty()
                )
                applySnapshot(cached, structureChanged = true)
                OrionRecoveryLogger.logPlaybackStarted("cache")
                deviceLogCollector.logPlayback(
                    "Started from cache: ${cached.playlistInfo?.name} assets=${cached.playlistAssets.size}"
                )
                ensureBackgroundServicesStarted()
            } else {
                _uiState.value = PlaybackUiState.Loading
            }
            updatePlaybackHealthState()

            if (isDisplayingContent()) {
                OrionRecoveryLogger.logBackgroundSyncStarted("startup")
            }
            val outcome = withContext(Dispatchers.IO) {
                contentSyncCoordinator.syncContent(
                    current = currentSnapshot(),
                    force = true,
                    onDownloadProgress = if (!isDisplayingContent()) {
                        { completed, total ->
                            viewModelScope.launch {
                                if (!isDisplayingContent()) {
                                    _uiState.value = PlaybackUiState.Downloading(completed, total)
                                }
                            }
                        }
                    } else {
                        null
                    }
                )
            }
            when (outcome) {
                is SyncOutcome.Unpaired -> handleUnpaired()
                is SyncOutcome.NoContent -> {
                    if (!isDisplayingContent()) {
                        _uiState.value = PlaybackUiState.NoContent
                    }
                }
                is SyncOutcome.Failed -> {
                    if (!isDisplayingContent()) {
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
                    if (!isDisplayingContent()) {
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
            updatePlaybackHealthState()
        }
    }

    private fun recoverPlayback(reason: String) {
        viewModelScope.launch {
            OrionRecoveryLogger.logPlaybackRestart(reason)
            activePopSession?.takeIf { !it.finalized }?.let {
                finalizePopSession("FAILED", it)
            }
            slotGeneration++
            advanceJob?.cancel()
            pendingSnapshot = null

            val cached = contentSyncCoordinator.loadCachedSnapshot()
            if (cached != null && snapshotIsPlayable(cached)) {
                applySnapshot(cached, structureChanged = true)
                ensureBackgroundServicesStarted()
                OrionRecoveryLogger.logRecoveryCompleted(reason)
                return@launch
            }

            if (playlistAssets.isNotEmpty()) {
                startSlotLoop(resetGeneration = true)
                OrionRecoveryLogger.logRecoveryCompleted(reason)
                return@launch
            }

            startPlayback()
            OrionRecoveryLogger.logRecoveryCompleted(reason)
        }
    }

    private fun updatePlaybackHealthState() {
        healthMonitor.recordPlaybackExpected(
            securePrefs.isAuthenticated() && playlistAssets.isNotEmpty()
        )
        healthMonitor.recordPlaybackActive(isDisplayingContent())
        healthMonitor.pulsePlayback()
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
                delay(SyncConfig.FULL_SYNC_POLL_INTERVAL_MS)
                requestContentSync(force = true, reason = "full.poll")
            }
        }
    }

    private fun requestContentSync(force: Boolean = false, reason: String = "manual") {
        val displaying = isDisplayingContent()
        val index = _currentAssetIndex.value
        val assetName = playlistAssets.getOrNull(index)?.name.orEmpty()
        PlaybackEngineLogger.logSyncStarted(reason, displaying, index, assetName)

        viewModelScope.launch {
            val current = currentSnapshot()
            val outcome = withContext(Dispatchers.IO) {
                contentSyncCoordinator.syncContent(
                    current = current,
                    force = force,
                    onDownloadProgress = if (current == null && !displaying) {
                        { completed, total ->
                            viewModelScope.launch {
                                if (!isDisplayingContent()) {
                                    _uiState.value = PlaybackUiState.Downloading(completed, total)
                                }
                            }
                        }
                    } else {
                        null
                    }
                )
            }
            when (outcome) {
                is SyncOutcome.Unpaired -> handleUnpaired()
                is SyncOutcome.NoContent -> {
                    if (!isDisplayingContent()) {
                        stopPlayback()
                        _uiState.value = PlaybackUiState.NoContent
                    }
                }
                is SyncOutcome.Updated -> {
                    applySnapshotIfPlayable(outcome.snapshot, outcome.structureChanged)
                    telemetryRepository.flushAll()
                    PlaybackEngineLogger.logSyncCompleted(
                        outcome = "Updated",
                        displayingContent = isDisplayingContent(),
                        staged = pendingSnapshot != null
                    )
                }
                is SyncOutcome.Failed -> {
                    PlaybackEngineLogger.logSyncCompleted(
                        outcome = "Failed",
                        displayingContent = isDisplayingContent(),
                        staged = false
                    )
                    if (!outcome.keepPlaying && !isDisplayingContent()) {
                        val msg = outcome.message.ifBlank { "Content download failed" }
                        SyncDiagnostics.logWaitingScreen(msg, "requestContentSync.Failed")
                        _uiState.value = PlaybackUiState.WaitingForInitialDownload(msg)
                    }
                }
                is SyncOutcome.Unchanged -> {
                    PlaybackEngineLogger.logSyncCompleted(
                        outcome = "Unchanged",
                        displayingContent = isDisplayingContent(),
                        staged = pendingSnapshot != null
                    )
                }
                is SyncOutcome.Downloading -> Unit
            }
        }
    }

    private fun isDisplayingContent(): Boolean =
        uiState.value is PlaybackUiState.PlayingFullScreen

    private fun applySnapshotIfPlayable(snapshot: PlaybackSnapshot, structureChanged: Boolean) {
        if (snapshotIsPlayable(snapshot)) {
            applySnapshot(snapshot, structureChanged)
        } else if (isDisplayingContent()) {
            PlaybackEngineLogger.logPlaybackContinuedDuringSync(
                assetName = playlistAssets.getOrNull(_currentAssetIndex.value)?.name.orEmpty(),
                queueIndex = _currentAssetIndex.value
            )
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

    private fun normalizedSnapshot(snapshot: PlaybackSnapshot): PlaybackSnapshot {
        val newAssets = snapshot.playlistAssets.ifEmpty { snapshot.assets }.inPlaylistOrder()
        return snapshot.copy(
            assets = newAssets,
            playlistAssets = newAssets,
            currentIndex = snapshot.currentIndex.coerceIn(0, (newAssets.size - 1).coerceAtLeast(0))
        )
    }

    private fun stagePendingSnapshot(snapshot: PlaybackSnapshot, reason: String) {
        val normalized = normalizedSnapshot(snapshot)
        val currentAssetId = playlistAssets.getOrNull(_currentAssetIndex.value)?.id
        val resolvedIndex = when {
            currentAssetId != null -> {
                val idxInNew = normalized.playlistAssets.indexOfFirst { it.id == currentAssetId }
                if (idxInNew >= 0) idxInNew else 0
            }
            else -> _currentAssetIndex.value.coerceIn(
                0,
                (normalized.playlistAssets.size - 1).coerceAtLeast(0)
            )
        }
        pendingSnapshot = normalized.copy(currentIndex = resolvedIndex)
        localFiles = normalized.localFiles
        currentTickers = normalized.tickers
        playlistInfo = normalized.playlistInfo
        playlistVersion = normalized.playlistVersion
        PlaybackEngineLogger.logQueueStaged(
            oldSize = playlistAssets.size,
            newSize = normalized.playlistAssets.size,
            playlistVersion = normalized.playlistVersion,
            reason = reason,
            currentAsset = playlistAssets.getOrNull(_currentAssetIndex.value)?.name.orEmpty()
        )
        PlaybackSlotLogger.logPendingSnapshotDeferred(
            reason = reason,
            currentAsset = playlistAssets.getOrNull(_currentAssetIndex.value)?.name.orEmpty()
        )
    }

    private fun applySnapshot(snapshot: PlaybackSnapshot, structureChanged: Boolean) {
        val normalized = normalizedSnapshot(snapshot)
        val newAssets = normalized.playlistAssets

        PlaylistManifestLogger.logManifest(
            source = "apply",
            playlistId = normalized.playlistInfo?.id,
            playlistName = normalized.playlistInfo?.name,
            playlistVersion = normalized.playlistVersion,
            assets = newAssets
        )

        val orderChanged = playlistAssets.map { it.id } != newAssets.map { it.id }
        val sizeChanged = playlistAssets.size != newAssets.size
        val versionChanged = playlistVersion != normalized.playlistVersion
        val metadataChanged = playlistAssets.isNotEmpty() &&
            newAssets.playlistManifestChangedFrom(playlistAssets)
        val structuralChange = orderChanged || sizeChanged || structureChanged

        PlaybackEngineLogger.logDurationChangesFromSync(
            previous = playlistAssets,
            synced = newAssets,
            playlistVersion = normalized.playlistVersion
        )

        localFiles = normalized.localFiles
        currentTickers = normalized.tickers
        playlistInfo = normalized.playlistInfo
        playlistVersion = normalized.playlistVersion

        if (isDisplayingContent()) {
            mergePlaylistMetadataInPlace(newAssets)
            if (structuralChange) {
                stagePendingSnapshot(
                    normalized,
                    reason = when {
                        orderChanged -> "order changed"
                        sizeChanged -> "asset count changed"
                        else -> "structure changed"
                    }
                )
                return
            }
            if (metadataChanged) {
                emitFullScreen()
            }
            return
        }

        pendingSnapshot = null
        mode = PlaybackMode.FULL_SCREEN
        assets = newAssets
        playlistAssets = newAssets
        _currentAssetIndex.value = normalized.currentIndex
        healthMonitor.recordQueueProgress(normalized.currentIndex)

        PlaylistManifestLogger.logQueue(
            playlist = playlistInfo,
            playlistVersion = playlistVersion,
            assets = playlistAssets,
            currentIndex = _currentAssetIndex.value
        )

        if (structuralChange || metadataChanged || versionChanged) {
            PlaylistRefreshLogger.logQueueRebuilt(
                playlistName = playlistInfo?.name.orEmpty(),
                assetCount = playlistAssets.size,
                resetIndex = structuralChange,
                newVersion = playlistVersion
            )
        }

        emitFullScreen()
        SyncDiagnostics.logPlaybackStart(
            playlistName = playlistInfo?.name.orEmpty(),
            assetName = playlistAssets.getOrNull(_currentAssetIndex.value)?.name.orEmpty(),
            assetIndex = _currentAssetIndex.value,
            total = playlistAssets.size
        )

        startSlotLoop(resetGeneration = true)
    }

    /** Apply latest CMS metadata (duration, name, etc.) without reordering the active queue. */
    private fun mergePlaylistMetadataInPlace(syncedAssets: List<AssetInfo>) {
        if (playlistAssets.isEmpty()) return
        val syncedById = syncedAssets.associateBy { it.id }
        playlistAssets = playlistAssets.map { current ->
            val synced = syncedById[current.id] ?: return@map current
            if (current.cmsDurationSeconds != synced.cmsDurationSeconds) {
                PlaybackEngineLogger.logDurationMergedInMemory(
                    assetName = current.name,
                    oldDurationSec = current.cmsDurationSeconds,
                    newDurationSec = synced.cmsDurationSeconds
                )
            }
            synced
        }
        assets = playlistAssets
    }

    private fun startSlotLoop(resetGeneration: Boolean) {
        ensureBackgroundServicesStarted()
        if (resetGeneration) {
            slotGeneration++
            advanceJob?.cancel()
        }
        scheduleCurrentAsset()
    }

    private fun scheduleCurrentAsset() {
        val generation = slotGeneration
        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            runCurrentSlot(generation)
        }
    }

    private suspend fun runCurrentSlot(generation: Int) {
        if (generation != slotGeneration) return

        val index = _currentAssetIndex.value
        if (index >= playlistAssets.size) return

        val asset = playlistAssets[index]
        val playlistName = playlistInfo?.name.orEmpty()
        val configuredMs = asset.playbackSlotDurationMs()
        val slotStart = Instant.now()

        contentReadyDeferred = CompletableDeferred()
        videoEndedDeferred = CompletableDeferred()

        if (!asset.isPlayable(localFiles)) {
            PlaybackSlotLogger.logSlotSkipped(index, asset, "not playable locally")
            beginPopSession(asset, playlistName)
            finalizePopSession("FAILED")
            advanceToNextAsset()
            return
        }

        beginPopSession(asset, playlistName)
        if (abortSlotForGeneration(generation, played = false)) return

        val sessionId = activePopSession?.sessionId.orEmpty()
        PlaybackSlotLogger.logSlotStarted(index, asset, configuredMs, sessionId)
        healthMonitor.recordSlotStarted(configuredMs)

        val result = when (asset.normalizedType()) {
            AssetType.VIDEO -> {
                healthMonitor.recordVideoSlotActive(true)
                try {
                    playVideoSlot(asset, configuredMs)
                } finally {
                    healthMonitor.recordVideoSlotActive(false)
                }
            }
            else -> playTimedSlot(asset, configuredMs)
        }

        if (abortSlotForGeneration(generation, played = true, result = result, slotStart = slotStart)) return

        val actualElapsedSec = elapsedSecondsSince(activePopSession?.effectiveStartTime(), Instant.now())
        finalizePopSession(result, actualElapsedSeconds = if (result == "VERIFIED") actualElapsedSec else null)
        val actualMs = Duration.between(slotStart, Instant.now()).toMillis()
        PlaybackSlotLogger.logSlotEnded(
            queueIndex = index,
            asset = asset,
            configuredDurationMs = configuredMs,
            actualDurationMs = actualMs,
            sessionId = sessionId,
            result = result
        )
        healthMonitor.recordSlotEnded()

        if (applyPendingSnapshotIfAny()) {
            emitFullScreen()
        }
        advanceToNextAsset()
    }

    private suspend fun abortSlotForGeneration(
        generation: Int,
        played: Boolean,
        result: String = "FAILED",
        slotStart: Instant = Instant.now()
    ): Boolean {
        if (generation == slotGeneration) return false
        activePopSession?.takeIf { !it.finalized }?.let { session ->
            val elapsed = if (played && result == "VERIFIED") {
                elapsedSecondsSince(session.effectiveStartTime(), Instant.now())
            } else {
                null
            }
            finalizePopSession(
                status = if (played) result else "FAILED",
                session = session,
                actualElapsedSeconds = elapsed
            )
        }
        return true
    }

    private fun elapsedSecondsSince(start: Instant?, end: Instant): Int {
        if (start == null) return 1
        return Duration.between(start, end).seconds.toInt().coerceAtLeast(1)
    }

    private suspend fun playTimedSlot(asset: AssetInfo, configuredMs: Long): String {
        if (asset.deferPopStartUntilReady()) {
            if (!awaitContentReady(asset.name)) return "FAILED"
        }
        val startTime = activePopSession?.contentReadyTime ?: Instant.now()
        waitForConfiguredDuration(asset.id, startTime)
        return "VERIFIED"
    }

    private suspend fun playVideoSlot(asset: AssetInfo, configuredMs: Long): String {
        if (asset.deferPopStartUntilReady()) {
            if (!awaitContentReady(asset.name)) return "FAILED"
        }
        val latest = playlistAssets.find { it.id == asset.id } ?: asset
        return if (latest.hasExplicitDuration()) {
            val startTime = activePopSession?.contentReadyTime ?: Instant.now()
            waitForConfiguredDuration(asset.id, startTime)
            videoStopToken++
            emitFullScreen()
            delay(150L)
            val configured = latest.playbackSlotDurationMs()
            PlaybackEngineLogger.logVideoDurationStop(
                assetName = latest.name,
                configuredMs = configured,
                actualMs = Duration.between(startTime, Instant.now()).toMillis()
            )
            "VERIFIED"
        } else {
            val completed = awaitVideoEnded(asset.name)
            if (completed) {
                PlaybackEngineLogger.logVideoCompleted(asset.name)
            }
            if (completed) "VERIFIED" else "FAILED"
        }
    }

    /**
     * Polls the live queue so CMS duration updates apply mid-slot without restarting playback.
     */
    private suspend fun waitForConfiguredDuration(assetId: String, startTime: Instant) {
        while (true) {
            val asset = playlistAssets.find { it.id == assetId } ?: return
            val targetMs = asset.playbackSlotDurationMs()
            val elapsed = Duration.between(startTime, Instant.now()).toMillis()
            if (elapsed >= targetMs) {
                PlaybackEngineLogger.logDurationUsedDuringPlayback(
                    assetName = asset.name,
                    configuredMs = targetMs,
                    actualMs = elapsed
                )
                return
            }
            delay(minOf(targetMs - elapsed, 250L))
        }
    }

    private suspend fun awaitContentReady(assetName: String): Boolean {
        val ready = withTimeoutOrNull(CONTENT_READY_TIMEOUT_MS) {
            contentReadyDeferred?.await()
        } != null
        if (ready) {
            PlaybackSlotLogger.logContentReady(assetName, activePopSession?.sessionId.orEmpty())
        } else {
            PlaybackSlotLogger.logSlotSkipped(
                _currentAssetIndex.value,
                playlistAssets[_currentAssetIndex.value],
                "content ready timeout"
            )
        }
        return ready
    }

    private suspend fun awaitVideoEnded(assetName: String): Boolean =
        withTimeoutOrNull(VIDEO_END_TIMEOUT_MS) {
            videoEndedDeferred?.await()
        } != null

    private suspend fun beginPopSession(asset: AssetInfo, playlistName: String) {
        activePopSession?.takeIf { !it.finalized }?.let { dangling ->
            finalizePopSession("VERIFIED", dangling)
        }

        val session = PlaybackSession(
            assetId = asset.id,
            assetName = asset.name,
            playlistName = playlistName,
            configuredDurationSeconds = (asset.playbackSlotDurationMs() / 1000L).toInt().coerceAtLeast(1),
            slotStartTime = Instant.now()
        )
        if (!asset.deferPopStartUntilReady()) {
            session.contentReadyTime = session.slotStartTime
        }
        activePopSession = session
        PopSessionLogger.logSessionStarted(session)
        emitFullScreen()
    }

    fun onVideoRendererPulse() {
        healthMonitor.recordVideoRendererPulse()
    }

    fun onPlaybackStarted(assetName: String) {
        val session = activePopSession ?: return
        if (session.finalized || session.assetName != assetName) return
        if (session.contentReadyTime != null) return
        session.contentReadyTime = Instant.now()
        contentReadyDeferred?.complete(Unit)
        PopSessionLogger.logContentReady(session, session.contentReadyTime!!)
        healthMonitor.pulsePlayback()
    }

    fun onVideoEnded(assetName: String) {
        val session = activePopSession ?: return
        if (session.finalized || session.assetName != assetName) return
        videoEndedDeferred?.complete(Unit)
    }

    private suspend fun finalizePopSession(
        status: String,
        session: PlaybackSession? = activePopSession,
        actualElapsedSeconds: Int? = null
    ) {
        val active = session ?: return
        if (active.finalized) return
        active.finalized = true

        val startTime = active.effectiveStartTime()
        val endTime = active.effectiveEndTime(status, actualElapsedSeconds)
        val durationSeconds = active.effectiveDurationSeconds(status, actualElapsedSeconds)

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

    private fun commitPendingQueueSwap(): Boolean {
        val pending = pendingSnapshot ?: return false
        pendingSnapshot = null
        val oldSize = playlistAssets.size
        val newAssets = pending.playlistAssets.ifEmpty { pending.assets }.inPlaylistOrder()
        mode = PlaybackMode.FULL_SCREEN
        assets = newAssets
        playlistAssets = newAssets
        localFiles = pending.localFiles
        playlistInfo = pending.playlistInfo
        playlistVersion = pending.playlistVersion
        currentTickers = pending.tickers
        _currentAssetIndex.value = pending.currentIndex.coerceIn(0, (newAssets.size - 1).coerceAtLeast(0))
        PlaybackEngineLogger.logQueueSwapped(
            oldSize = oldSize,
            newSize = newAssets.size,
            playlistVersion = playlistVersion,
            newIndex = _currentAssetIndex.value
        )
        PlaybackSlotLogger.logPendingSnapshotApplied(
            assetCount = newAssets.size,
            resetIndex = pending.currentIndex == 0
        )
        PlaylistManifestLogger.logQueue(
            playlist = playlistInfo,
            playlistVersion = playlistVersion,
            assets = playlistAssets,
            currentIndex = _currentAssetIndex.value
        )
        return true
    }

    private fun applyPendingSnapshotIfAny(): Boolean = commitPendingQueueSwap()

    private fun advanceToNextAsset() {
        if (playlistAssets.isEmpty()) return
        val nextIndex = (_currentAssetIndex.value + 1) % playlistAssets.size
        _currentAssetIndex.value = nextIndex
        healthMonitor.recordQueueProgress(nextIndex)
        scheduleCurrentAsset()
    }

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
            playbackSessionId = activePopSession?.sessionId.orEmpty(),
            videoStopToken = videoStopToken
        )
        healthMonitor.recordPlaybackContext(
            assetName = asset.name,
            playlistName = playlistInfo?.name,
            queueSize = playlistAssets.size
        )
        updatePlaybackHealthState()
    }

    private fun snapshotIsPlayable(snapshot: PlaybackSnapshot): Boolean {
        val playlist = snapshot.playlistAssets.ifEmpty { snapshot.assets }
        return playlist.any { it.isPlayable(snapshot.localFiles) }
    }

    private fun stopPlayback() {
        slotGeneration++
        advanceJob?.cancel()
        advanceJob = null
        clearPlaybackState()
        pendingSnapshot = null
    }

    private fun clearPlaybackState() {
        assets = emptyList()
        playlistAssets = emptyList()
        localFiles = emptyMap()
        playlistInfo = null
        currentTickers = emptyList()
    }

    private fun handleUnpaired() {
        securePrefs.clearCredentials()
        _isUnpaired.value = true
    }

    private fun deviceDisplayName(): String =
        securePrefs.deviceName?.takeIf { it.isNotBlank() } ?: "Orion Display"

    fun onAssetFailed(assetName: String) {
        viewModelScope.launch {
            activePopSession?.takeIf { it.assetName == assetName && !it.finalized }?.let {
                finalizePopSession("FAILED", it)
            }
            if (applyPendingSnapshotIfAny()) {
                emitFullScreen()
            }
            slotGeneration++
            advanceJob?.cancel()
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
                    val health = deviceHealthReporter.snapshot(
                        playlistName = playlistInfo?.name,
                        currentAsset = currentAsset,
                        queueSize = playlistAssets.size
                    )
                    val permissions = devicePermissionReporter.snapshot()

                    val response = telemetryRepository.sendHeartbeat(
                        cpu = deviceHealthUtil.getCpuUsage().coerceAtLeast(0),
                        ram = deviceHealthUtil.getRamUsage().coerceAtLeast(0),
                        temp = deviceHealthUtil.getTemperature().coerceAtLeast(0),
                        currentContent = currentAsset,
                        deviceHealth = health,
                        permissions = permissions
                    )

                    response?.commands?.let { remoteCommandExecutor.dispatch(it) }

                    if (response?.syncRequired == true) {
                        requestContentSync(force = true, reason = "heartbeat.syncRequired")
                    } else if (contentSyncCoordinator.consumeRevisionIfChanged(response?.contentRevision)) {
                        requestContentSync(force = true, reason = "heartbeat.revision")
                    }

                    telemetryRepository.flushAll()
                    stabilityMonitor.reportIfDue(
                        queueSize = playlistAssets.size,
                        currentAsset = currentAsset,
                        playlistName = playlistInfo?.name
                    )
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
        recoveryCoordinator.unregisterPlaybackRestartHandler()
        remoteCommandExecutor.unregisterForceSyncHandler()
        remoteCommandExecutor.unregisterScreenshotWindowProvider()
        playerEventStreamClient.stop()
        slotGeneration++
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
    data class PlayingFullScreen(
        val asset: AssetInfo,
        val localFile: File?,
        val currentIndex: Int,
        val totalAssets: Int,
        val playlistName: String,
        val tickers: List<TickerDisplayConfig> = emptyList(),
        val playbackSessionId: String = "",
        val videoStopToken: Long = 0L
    ) : PlaybackUiState()
    data class Error(val message: String) : PlaybackUiState()
}
