package com.orion.player.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.analytics.PlaybackSession
import com.orion.player.data.analytics.PopLogRecord
import com.orion.player.data.analytics.PopSessionLogger
import com.orion.player.data.analytics.PopSessionRecorder
import com.orion.player.data.cache.CacheDownloadLogger
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.AutoStartLogger
import com.orion.player.data.recovery.OrionRecoveryLogger
import com.orion.player.data.recovery.PlaybackRecoveryCoordinator
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.playback.PlaybackEngineLogger
import com.orion.player.data.playback.PlaybackSlotLogger
import com.orion.player.data.playback.PlaylistManifestLogger
import com.orion.player.data.playback.inPlaylistOrder
import com.orion.player.data.playback.DocumentFormat
import com.orion.player.data.playback.resolvePlaybackDuration
import com.orion.player.data.playback.playbackSlotDurationMs
import com.orion.player.data.config.DevicePlaybackDurations
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.deferPopStartUntilReady
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.data.enterprise.DeviceLogCollector
import com.orion.player.data.enterprise.RemoteCommandExecutor
import com.orion.player.data.remote.HeartbeatResponse
import com.orion.player.data.analytics.PopLogFlushScheduler
import com.orion.player.data.config.DeviceConfigManager
import com.orion.player.data.registration.DeviceRegistrationManager
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.data.schedule.ActiveScheduleTracker
import com.orion.player.data.schedule.AssignedPlaylistStore
import com.orion.player.data.schedule.SchedulingConfig
import com.orion.player.data.schedule.ScheduleExpiryController
import com.orion.player.data.schedule.ScheduleLogger
import com.orion.player.data.telemetry.DeviceHeartbeatScheduler
import com.orion.player.data.sync.ContentSyncCoordinator
import com.orion.player.data.sync.ContentSyncScheduler
import com.orion.player.data.sync.InitialSyncCoordinator
import com.orion.player.data.sync.PostPairingBootstrap
import com.orion.player.data.sync.PlaybackMode
import com.orion.player.data.sync.PlaybackSnapshot
import com.orion.player.data.sync.PlaylistRefreshLogger
import com.orion.player.data.sync.PlayerEventStreamClient
import com.orion.player.data.sync.RevisionPollScheduler
import com.orion.player.data.sync.SyncDiagnostics
import com.orion.player.data.sync.SyncOutcome
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.TickerStateStore
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
    private val popSessionRecorder: PopSessionRecorder,
    private val networkMonitor: NetworkMonitor,
    private val securePrefs: SecurePrefs,
    private val healthMonitor: PlayerHealthMonitor,
    private val recoveryCoordinator: PlaybackRecoveryCoordinator,
    private val remoteCommandExecutor: RemoteCommandExecutor,
    private val deviceLogCollector: DeviceLogCollector,
    private val heartbeatScheduler: DeviceHeartbeatScheduler,
    private val contentSyncScheduler: ContentSyncScheduler,
    private val revisionPollScheduler: RevisionPollScheduler,
    private val initialSyncCoordinator: InitialSyncCoordinator,
    private val postPairingBootstrap: PostPairingBootstrap,
    private val deviceRegistrationManager: DeviceRegistrationManager,
    private val popLogFlushScheduler: PopLogFlushScheduler,
    private val deviceConfigManager: DeviceConfigManager,
    private val activeScheduleTracker: ActiveScheduleTracker,
    private val assignedPlaylistStore: AssignedPlaylistStore,
    private val scheduleExpiryController: ScheduleExpiryController,
    private val tickerStateStore: TickerStateStore
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
    private var pendingSnapshotFromSync = false

    private var advanceJob: Job? = null
    private var slotGeneration = 0
    private var contentReadyDeferred: CompletableDeferred<Unit>? = null
    private var videoEndedDeferred: CompletableDeferred<Unit>? = null

    private var videoStopToken = 0L

    private var backgroundServicesStarted = false

    private val _isUnpaired = MutableStateFlow(false)
    val isUnpaired: StateFlow<Boolean> = _isUnpaired.asStateFlow()

    val stretchToFit: StateFlow<Boolean> = deviceConfigManager.stretchToFit
    val overlayTickers: StateFlow<List<TickerDisplayConfig>> = tickerStateStore.tickers
    val tickerEnabled: StateFlow<Boolean> = deviceConfigManager.tickerEnabled

    /** Validated internet. Independent of sync/API success. */
    val isOnline: StateFlow<Boolean> = networkMonitor.online

    init {
        healthMonitor.recordStartupInit()
        healthMonitor.registerSlotLoopAliveChecker { advanceJob?.isActive == true }
        viewModelScope.launch {
            heartbeatScheduler.responses.collect { response ->
                handleHeartbeatResponse(response)
            }
        }
        viewModelScope.launch {
            deviceRegistrationManager.registrationEvents.collect {
                handleRegistrationInvalidated()
            }
        }
        viewModelScope.launch {
            deviceRegistrationManager.requiresPairing.collect { needsPairing ->
                if (needsPairing) handleRegistrationInvalidated()
            }
        }
        recoveryCoordinator.registerPlaybackRestartHandler { reason ->
            recoverPlayback(reason)
        }
        contentSyncScheduler.registerFullSyncHandler { reason ->
            requestContentSync(force = false, reason = reason)
        }
        revisionPollScheduler.registerSyncHandler { reason ->
            requestContentSync(force = true, reason = reason)
            true
        }
        contentSyncCoordinator.registerRetrySyncHandler {
            requestContentSync(force = true, reason = "sync.retry")
        }
        if (SchedulingConfig.ENABLED) {
            startScheduleExpiryController()
        } else {
            SchedulingConfig.logDisabled("PlaybackViewModel.init")
        }
        initialSyncCoordinator.registerSyncHandler { reason, commandId ->
            executeForcedSync(reason, commandId)
        }
        viewModelScope.launch {
            if (securePrefs.pairingBootstrapPending) {
                securePrefs.pairingBootstrapPending = false
                postPairingBootstrap.onPairingCompleted()
            }
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
        heartbeatScheduler.start()
        contentSyncScheduler.start()
        revisionPollScheduler.start()
    }

    private fun currentSnapshot(): PlaybackSnapshot? {
        val overlay = tickerStateStore.tickers.value
        if (playlistAssets.isEmpty() && assets.isEmpty() && currentTickers.isEmpty() && overlay.isEmpty()) {
            return null
        }
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
            tickers = overlay.ifEmpty { currentTickers },
            zones = emptyList(),
            zoneIndices = emptyMap()
        )
    }

    private fun startPlayback() {
        viewModelScope.launch {
            if (securePrefs.isAuthenticated()) {
                ensureBackgroundServicesStarted()
            }
            SyncDiagnostics.logStartupDevice(securePrefs)
            AutoStartLogger.playerInitStart("playback.start")
            healthMonitor.recordPlaybackExpected(securePrefs.isAuthenticated())

            // Cache first, always: content must appear without waiting for the CMS or
            // for the network to come up after a reboot.
            val cached = contentSyncCoordinator.loadCachedSnapshot()
            if (cached != null && snapshotIsPlayable(cached)) {
                OrionRecoveryLogger.logCachedPlaylistLoaded(
                    assetCount = cached.playlistAssets.size,
                    playlistName = cached.playlistInfo?.name.orEmpty()
                )
                applySnapshot(cached, structureChanged = true)
                AutoStartLogger.cachePlaybackStart(
                    playlistId = cached.playlistInfo?.id,
                    playlistName = cached.playlistInfo?.name,
                    assetCount = cached.playlistAssets.size
                )
                OrionRecoveryLogger.logPlaybackStarted("cache")
                deviceLogCollector.logPlayback(
                    "Started from cache: ${cached.playlistInfo?.name} assets=${cached.playlistAssets.size}"
                )
                ensureBackgroundServicesStarted()
            } else {
                AutoStartLogger.cachePlaybackUnavailable(
                    if (cached == null) "no cached playlist" else "cached assets not playable"
                )
                _uiState.value = PlaybackUiState.Loading
            }
            updatePlaybackHealthState()

            if (isDisplayingContent()) {
                OrionRecoveryLogger.logBackgroundSyncStarted("startup")
            }
            AutoStartLogger.cmsSyncStart()
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
                    // Offline or CMS unreachable: cached playback keeps running untouched.
                    AutoStartLogger.cmsSyncFailed(
                        reason = outcome.message.ifBlank { "content download failed" },
                        playingFromCache = isDisplayingContent()
                    )
                    if (!isDisplayingContent()) {
                        val reason = outcome.message.ifBlank { "Content download failed" }
                        SyncDiagnostics.logWaitingScreen(reason, "startPlayback.Failed")
                        _uiState.value = PlaybackUiState.WaitingForInitialDownload(reason)
                    }
                }
                is SyncOutcome.Updated -> {
                    AutoStartLogger.cmsSyncSuccess("updated")
                    applySnapshotIfPlayable(outcome.snapshot, outcome.structureChanged)
                    telemetryRepository.flushAll()
                }
                is SyncOutcome.Unchanged -> {
                    AutoStartLogger.cmsSyncSuccess("unchanged")
                    if (!isDisplayingContent()) {
                        val fallback = contentSyncCoordinator.loadCachedSnapshot()
                        if (fallback != null && snapshotIsPlayable(fallback)) {
                            applySnapshot(fallback, structureChanged = true)
                        } else if (tickerStateStore.tickers.value.isNotEmpty()) {
                            _uiState.value = PlaybackUiState.NoContent
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
            pendingSnapshotFromSync = false

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
                    if (!SchedulingConfig.ENABLED) {
                        SchedulingConfig.logDisabled("network.reconnected")
                        requestContentSync(force = true, reason = "network.reconnected")
                        return@collect
                    }
                    val pending = activeScheduleTracker.reconcileAfterReconnect()
                    val reason = if (pending.shouldSync) {
                        pending.reason ?: "network.reconnected"
                    } else {
                        "network.reconnected"
                    }
                    if (pending.shouldSync && pending.reason == ActiveScheduleTracker.REASON_ENDED) {
                        leaveExpiredSchedule(reason)
                    } else {
                        requestContentSync(force = true, reason = reason)
                    }
                }
            }
        }
    }

    /**
     * One expiry timer for the active schedule. Fires at endDateTime using
     * synchronized server time and immediately updates the playback source.
     */
    private fun startScheduleExpiryController() {
        if (!SchedulingConfig.ENABLED) {
            SchedulingConfig.logDisabled("startScheduleExpiryController")
            return
        }
        scheduleExpiryController.setOnExpired {
            viewModelScope.launch {
                leaveExpiredSchedule(reason = "SCHEDULE_EXPIRED")
            }
        }
    }

    /**
     * Stop treating the expired scheduled playlist as the playback source.
     * Finalizes the current asset's PoP, switches immediately, then reconciles
     * with CMS. Does not restart the app, stop the service, unpair, or delete assets.
     */
    private fun leaveExpiredSchedule(reason: String) {
        if (!SchedulingConfig.ENABLED) {
            SchedulingConfig.logDisabled("leaveExpiredSchedule")
            return
        }
        viewModelScope.launch {
            val fromPlaylist = playlistInfo?.name ?: playlistInfo?.id

            activePopSession?.takeIf { !it.finalized }?.let { session ->
                val elapsed = elapsedSecondsSince(session.effectiveStartTime(), Instant.now())
                finalizePopSession("VERIFIED", session, actualElapsedSeconds = elapsed)
            }

            activeScheduleTracker.onExpiryTimerFired()
            activeScheduleTracker.consumeLocalExpiry(source = reason)

            val expiredPlaylistId = securePrefs.expiredSchedulePlaylistId
            if (expiredPlaylistId != null &&
                playlistInfo?.id != null &&
                playlistInfo?.id != expiredPlaylistId
            ) {
                requestContentSync(force = true, reason = reason)
                return@launch
            }

            slotGeneration++
            videoStopToken++
            advanceJob?.cancel()
            pendingSnapshot = null
            pendingSnapshotFromSync = false

            val fallback = withContext(Dispatchers.IO) { assignedPlaylistStore.load() }
            if (fallback != null && snapshotIsPlayable(fallback)) {
                if (playlistInfo?.id != fallback.playlistInfo?.id) {
                    applySnapshotImmediate(fallback, fromSync = true)
                    ScheduleLogger.playbackSwitch(
                        from = fromPlaylist,
                        to = fallback.playlistInfo?.name ?: fallback.playlistInfo?.id,
                        reason = "SCHEDULE_EXPIRED"
                    )
                }
                ScheduleLogger.reconcile(
                    scheduleId = securePrefs.expiredScheduleId,
                    nextPlaylistId = fallback.playlistInfo?.id
                )
            } else if (expiredPlaylistId != null && playlistInfo?.id == expiredPlaylistId) {
                ScheduleLogger.playbackSwitch(
                    from = fromPlaylist,
                    to = "none",
                    reason = "SCHEDULE_EXPIRED"
                )
                stopPlayback()
                _uiState.value = PlaybackUiState.NoContent
            }

            requestContentSync(force = true, reason = reason)
        }
    }

    /** Replace the playing queue now. Do not keep the expired playlist on screen. */
    private fun applySnapshotImmediate(
        snapshot: PlaybackSnapshot,
        fromSync: Boolean
    ) {
        val normalized = normalizedSnapshot(snapshot)
        val newAssets = normalized.playlistAssets
        pendingSnapshot = null
        pendingSnapshotFromSync = false
        mode = PlaybackMode.FULL_SCREEN
        assets = newAssets
        playlistAssets = newAssets
        localFiles = normalized.localFiles
        playlistInfo = normalized.playlistInfo
        playlistVersion = normalized.playlistVersion
        currentTickers = normalized.tickers
        _currentAssetIndex.value = normalized.currentIndex.coerceIn(
            0,
            (newAssets.size - 1).coerceAtLeast(0)
        )
        healthMonitor.recordQueueProgress(_currentAssetIndex.value)
        notifyPlaylistLive(fromSync)
        emitFullScreen()
        startSlotLoop(resetGeneration = true)
        PlaybackEngineLogger.logQueueSwapped(
            oldSize = 0,
            newSize = newAssets.size,
            playlistVersion = playlistVersion,
            newIndex = _currentAssetIndex.value
        )
    }

    private fun startRealtimeSync() {
        playerEventStreamClient.start(viewModelScope)

        viewModelScope.launch {
            playerEventStreamClient.syncTriggers.collect { trigger ->
                trigger.revision?.let { contentSyncCoordinator.onPushRevision(it) }
                requestContentSync(force = true, reason = trigger.reason)
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

    private suspend fun applySnapshotIfPlayable(snapshot: PlaybackSnapshot, structureChanged: Boolean) {
        if (snapshotIsPlayable(snapshot)) {
            applySnapshot(snapshot, structureChanged, fromSync = true)
        } else if (hasOverlayTicker(snapshot)) {
            currentTickers = overlayTickersFor(snapshot)
            if (!isDisplayingContent()) {
                _uiState.value = PlaybackUiState.NoContent
            }
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

    private fun hasOverlayTicker(snapshot: PlaybackSnapshot): Boolean =
        snapshot.tickers.isNotEmpty() || tickerStateStore.tickers.value.isNotEmpty()

    private fun overlayTickersFor(snapshot: PlaybackSnapshot): List<TickerDisplayConfig> =
        snapshot.tickers.ifEmpty { tickerStateStore.tickers.value }

    private fun normalizedSnapshot(snapshot: PlaybackSnapshot): PlaybackSnapshot {
        val newAssets = snapshot.playlistAssets.ifEmpty { snapshot.assets }.inPlaylistOrder()
        return snapshot.copy(
            assets = newAssets,
            playlistAssets = newAssets,
            currentIndex = snapshot.currentIndex.coerceIn(0, (newAssets.size - 1).coerceAtLeast(0))
        )
    }

    private suspend fun applySnapshot(
        snapshot: PlaybackSnapshot,
        structureChanged: Boolean,
        fromSync: Boolean = false
    ) {
        val normalized = normalizedSnapshot(snapshot)
        val newAssets = normalized.playlistAssets
        val previousAssets = playlistAssets
        val previousVersion = playlistVersion
        val wasDisplaying = isDisplayingContent()
        val currentIndex = _currentAssetIndex.value
        val currentAsset = previousAssets.getOrNull(currentIndex)

        PlaylistManifestLogger.logManifest(
            source = "apply",
            playlistId = normalized.playlistInfo?.id,
            playlistName = normalized.playlistInfo?.name,
            playlistVersion = normalized.playlistVersion,
            assets = newAssets
        )

        PlaybackEngineLogger.logDurationChangesFromSync(
            previous = previousAssets,
            synced = newAssets,
            playlistVersion = normalized.playlistVersion
        )

        if (newAssets.isEmpty()) {
            localFiles = normalized.localFiles
            currentTickers = normalized.tickers
            playlistInfo = normalized.playlistInfo
            playlistVersion = normalized.playlistVersion
            if (!wasDisplaying) {
                _uiState.value = PlaybackUiState.NoContent
            }
            return
        }

        val currentRemoved = currentAsset != null && newAssets.none { it.id == currentAsset.id }
        val resolvedIndex = resolveLiveQueueIndex(
            previousAssets = previousAssets,
            newAssets = newAssets,
            currentIndex = currentIndex,
            currentAssetId = currentAsset?.id
        )

        PlaylistRefreshLogger.logLoopRebuilt(
            playlistId = normalized.playlistInfo?.id,
            previousVersion = previousVersion,
            newVersion = normalized.playlistVersion,
            assetCount = newAssets.size,
            rebuiltLoop = true
        )

        pendingSnapshot = null
        pendingSnapshotFromSync = false
        localFiles = normalized.localFiles
        currentTickers = normalized.tickers
        playlistInfo = normalized.playlistInfo
        playlistVersion = normalized.playlistVersion
        mode = PlaybackMode.FULL_SCREEN
        assets = newAssets
        playlistAssets = newAssets
        _currentAssetIndex.value = resolvedIndex
        healthMonitor.recordQueueProgress(resolvedIndex)

        PlaylistManifestLogger.logQueue(
            playlist = playlistInfo,
            playlistVersion = playlistVersion,
            assets = playlistAssets,
            currentIndex = _currentAssetIndex.value
        )
        PlaylistRefreshLogger.logQueueRebuilt(
            playlistName = playlistInfo?.name.orEmpty(),
            assetCount = playlistAssets.size,
            resetIndex = currentRemoved || structureChanged && !wasDisplaying,
            newVersion = playlistVersion
        )

        if (wasDisplaying) {
            notifyPlaylistLive(fromSync)
            if (currentRemoved) {
                activePopSession?.takeIf { !it.finalized }?.let { session ->
                    finalizePopSession("FAILED", session)
                }
                emitFullScreen()
                startSlotLoop(resetGeneration = true)
                return
            }
            val playing = _uiState.value
            if (playing is PlaybackUiState.PlayingFullScreen &&
                playing.asset.id == playlistAssets.getOrNull(resolvedIndex)?.id
            ) {
                _uiState.value = playing.copy(
                    currentIndex = resolvedIndex,
                    totalAssets = playlistAssets.size,
                    playlistName = playlistInfo?.name.orEmpty(),
                    tickers = currentTickers
                )
            } else {
                emitFullScreen()
            }
            return
        }

        emitFullScreen()
        SyncDiagnostics.logPlaybackStart(
            playlistName = playlistInfo?.name.orEmpty(),
            assetName = playlistAssets.getOrNull(_currentAssetIndex.value)?.name.orEmpty(),
            assetIndex = _currentAssetIndex.value,
            total = playlistAssets.size
        )
        notifyPlaylistLive(fromSync)
        startSlotLoop(resetGeneration = true)
    }

    /**
     * Keep the currently playing occurrence when it is still in the new loop.
     * If it was removed, skip to the next remaining item (or the first).
     */
    private fun resolveLiveQueueIndex(
        previousAssets: List<AssetInfo>,
        newAssets: List<AssetInfo>,
        currentIndex: Int,
        currentAssetId: String?
    ): Int {
        if (newAssets.isEmpty()) return 0
        val last = newAssets.lastIndex
        val oldIds = previousAssets.map { it.id }
        val newIds = newAssets.map { it.id }
        if (oldIds == newIds) {
            return currentIndex.coerceIn(0, last)
        }
        if (currentAssetId == null) {
            return 0
        }
        if (newAssets.none { it.id == currentAssetId }) {
            val nextRemaining = previousAssets
                .drop(currentIndex + 1)
                .firstOrNull { old -> newAssets.any { it.id == old.id } }
                ?: previousAssets.firstOrNull { old -> newAssets.any { it.id == old.id } }
            val idx = nextRemaining?.let { match -> newAssets.indexOfFirst { it.id == match.id } } ?: 0
            return idx.coerceIn(0, last)
        }
        if (newAssets.getOrNull(currentIndex)?.id == currentAssetId) {
            return currentIndex
        }
        val idxInNew = newAssets.indexOfFirst { it.id == currentAssetId }
        return if (idxInNew >= 0) idxInNew else 0
    }

    /**
     * Report the playlist that just reached the screen. Only sync-driven playlists
     * commit schedule state; a cache restore must not clear the active schedule
     * before the first sync has confirmed what the CMS wants.
     */
    private fun notifyPlaylistLive(fromSync: Boolean) {
        if (fromSync) {
            activeScheduleTracker.onPlaylistActivated(
                playlistId = playlistInfo?.id,
                playlistName = playlistInfo?.name
            )
        } else {
            activeScheduleTracker.onCachedPlaylistRestored(playlistInfo?.name)
        }
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
        // Capture device defaults at slot start so CMS duration setting changes
        // apply from the next asset onward (current asset finishes normally).
        val defaultsAtSlotStart = deviceConfigManager.playbackDurations.value
        val resolved = asset.resolvePlaybackDuration(defaultsAtSlotStart, log = true)
        val configuredMs = resolved.durationMs
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
                    playVideoSlot(
                        queueIndex = index,
                        asset = asset,
                        configuredMs = configuredMs
                    )
                } finally {
                    healthMonitor.recordVideoSlotActive(false)
                }
            }
            else -> playTimedSlot(
                queueIndex = index,
                asset = asset,
                configuredMs = configuredMs
            )
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

    private suspend fun playTimedSlot(
        queueIndex: Int,
        asset: AssetInfo,
        configuredMs: Long
    ): String {
        if (asset.deferPopStartUntilReady()) {
            if (!awaitContentReady(asset.name)) return "FAILED"
        }
        val startTime = activePopSession?.contentReadyTime ?: Instant.now()
        waitForConfiguredDuration(queueIndex, startTime, configuredMs)
        return "VERIFIED"
    }

    private suspend fun playVideoSlot(
        queueIndex: Int,
        asset: AssetInfo,
        configuredMs: Long
    ): String {
        if (asset.deferPopStartUntilReady()) {
            if (!awaitContentReady(asset.name)) return "FAILED"
        }
        // Always use this queue index — never find-by-id (duplicates share ids).
        val latest = playlistAssets.getOrNull(queueIndex) ?: asset
        return if (configuredMs > 0L) {
            val startTime = activePopSession?.contentReadyTime ?: Instant.now()
            waitForConfiguredDuration(queueIndex, startTime, configuredMs)
            videoStopToken++
            emitFullScreen()
            delay(150L)
            PlaybackEngineLogger.logVideoDurationStop(
                assetName = latest.name,
                configuredMs = configuredMs,
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
     * Hold the duration captured at slot start. Playlist duration edits apply on
     * this asset's next play; the in-memory loop is already the new timeline.
     */
    private suspend fun waitForConfiguredDuration(
        queueIndex: Int,
        startTime: Instant,
        slotStartConfiguredMs: Long
    ) {
        val targetMs = slotStartConfiguredMs.coerceAtLeast(1L)
        while (true) {
            val elapsed = Duration.between(startTime, Instant.now()).toMillis()
            if (elapsed >= targetMs) {
                val asset = playlistAssets.getOrNull(queueIndex)
                    ?: playlistAssets.getOrNull(_currentAssetIndex.value)
                PlaybackEngineLogger.logDurationUsedDuringPlayback(
                    assetName = asset?.name.orEmpty(),
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
            playlistId = playlistInfo?.id,
            playlistName = playlistName,
            contentType = DocumentFormat.popContentLabel(asset),
            configuredDurationSeconds = (
                asset.playbackSlotDurationMs(deviceConfigManager.playbackDurations.value) / 1000L
            ).toInt().coerceAtLeast(1),
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
                deviceId = popDeviceId(),
                playlistId = active.playlistId ?: playlistInfo?.id,
                playlistName = active.playlistName,
                assetId = active.assetId,
                assetName = active.assetName,
                startTime = startTime,
                endTime = endTime
            )
        } else {
            PopLogRecord.verified(
                deviceName = deviceDisplayName(),
                deviceId = popDeviceId(),
                playlistId = active.playlistId ?: playlistInfo?.id,
                playlistName = active.playlistName,
                assetId = active.assetId,
                assetName = active.assetName,
                startTime = startTime,
                endTime = endTime,
                durationSeconds = durationSeconds
            )
        }

        activeScheduleTracker.logPopEvent(
            playlistId = record.playlistId,
            assetId = record.assetId,
            startTime = startTime.toString(),
            endTime = endTime.toString(),
            durationSeconds = durationSeconds,
            status = status
        )

        if (active === activePopSession) {
            activePopSession = null
        }
        queuePopLog(record)
        healthMonitor.recordPopGenerated()
    }

    private fun commitPendingQueueSwap(): Boolean {
        val pending = pendingSnapshot ?: return false
        val fromSync = pendingSnapshotFromSync
        pendingSnapshot = null
        pendingSnapshotFromSync = false
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
        notifyPlaylistLive(fromSync)
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
        pendingSnapshotFromSync = false
    }

    private fun clearPlaybackState() {
        assets = emptyList()
        playlistAssets = emptyList()
        localFiles = emptyMap()
        playlistInfo = null
        currentTickers = emptyList()
    }

    private fun handleUnpaired() {
        // Session clear already performed by DeviceRegistrationManager on 401.
        handleRegistrationInvalidated()
    }

    private fun handleRegistrationInvalidated() {
        if (_isUnpaired.value) return
        stopBackgroundServices()
        slotGeneration++
        advanceJob?.cancel()
        clearPlaybackState()
        pendingSnapshot = null
        pendingSnapshotFromSync = false
        activePopSession = null
        activeScheduleTracker.clear()
        assignedPlaylistStore.clear()
        tickerStateStore.clear()
        healthMonitor.recordPlaybackExpected(false)
        _isUnpaired.value = true
    }

    private fun stopBackgroundServices() {
        backgroundServicesStarted = false
        heartbeatScheduler.stop()
        contentSyncScheduler.stop()
        revisionPollScheduler.stop()
        popLogFlushScheduler.stop()
        playerEventStreamClient.stop()
    }

    private fun deviceDisplayName(): String =
        securePrefs.deviceName?.takeIf { it.isNotBlank() } ?: "Orion Display"

    private fun popDeviceId(): String =
        securePrefs.cmsDeviceId?.takeIf { it.isNotBlank() } ?: securePrefs.getOrCreateHardwareId()

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
        // Still mark content ready so the configured slot duration continues
        // (fallback / blank content) instead of waiting for the 30s ready timeout.
        onPlaybackStarted(assetName)
        viewModelScope.launch {
            activePopSession?.takeIf { it.assetName == assetName && !it.finalized }?.let {
                // Keep session running; PoP will finalize as VERIFIED/FAILED at slot end.
            }
        }
    }

    private fun handleHeartbeatResponse(response: HeartbeatResponse) {
        contentSyncScheduler.updateInterval(response.syncIntervalSeconds)
        if (!SchedulingConfig.ENABLED) {
            SchedulingConfig.logDisabled("heartbeat")
            if (response.syncRequired == true) {
                requestContentSync(force = true, reason = "heartbeat.syncRequired")
            } else if (contentSyncCoordinator.consumeRevisionIfChanged(response.contentRevision)) {
                requestContentSync(force = true, reason = "heartbeat.revision")
            }
            return
        }
        val scheduleSignal = activeScheduleTracker.observe(
            schedule = response.activeSchedule,
            source = "heartbeat",
            serverTime = response.serverTime
        )
        val expired = activeScheduleTracker.consumeLocalExpiry(source = "heartbeat")
        if (expired || scheduleSignal.reason == ActiveScheduleTracker.REASON_ENDED) {
            leaveExpiredSchedule(scheduleSignal.reason ?: "schedule.ended")
        } else if (response.syncRequired == true) {
            requestContentSync(force = true, reason = "heartbeat.syncRequired")
        } else if (contentSyncCoordinator.consumeRevisionIfChanged(response.contentRevision)) {
            requestContentSync(force = true, reason = "heartbeat.revision")
        } else if (scheduleSignal.shouldSync) {
            requestContentSync(
                force = true,
                reason = scheduleSignal.reason ?: "schedule.changed"
            )
        }
    }

    private suspend fun executeForcedSync(reason: String, commandId: String?): Boolean {
        val displaying = isDisplayingContent()
        val outcome = withContext(Dispatchers.IO) {
            contentSyncCoordinator.syncContent(
                current = currentSnapshot(),
                force = true,
                completedCommandId = commandId,
                onDownloadProgress = if (!displaying) {
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

        val success = when (outcome) {
            is SyncOutcome.Unpaired -> {
                handleUnpaired()
                false
            }
            is SyncOutcome.Updated -> {
                applySnapshotIfPlayable(outcome.snapshot, outcome.structureChanged)
                telemetryRepository.flushAll()
                true
            }
            is SyncOutcome.Unchanged -> {
                val fallback = contentSyncCoordinator.loadCachedSnapshot()
                if (!isDisplayingContent() && fallback != null && snapshotIsPlayable(fallback)) {
                    applySnapshot(fallback, structureChanged = true)
                } else if (!isDisplayingContent() && tickerStateStore.tickers.value.isNotEmpty()) {
                    _uiState.value = PlaybackUiState.NoContent
                }
                true
            }
            is SyncOutcome.NoContent -> {
                if (!isDisplayingContent()) {
                    _uiState.value = PlaybackUiState.NoContent
                }
                true
            }
            is SyncOutcome.Failed -> {
                if (!isDisplayingContent()) {
                    val msg = outcome.message.ifBlank { "Content download failed" }
                    _uiState.value = PlaybackUiState.WaitingForInitialDownload(msg)
                }
                false
            }
            is SyncOutcome.Downloading -> false
        }

        if (success) {
            initialSyncCoordinator.markInitialDownloadStarted()
            ensureBackgroundServicesStarted()
        }
        return success
    }

    private suspend fun queuePopLog(record: PopLogRecord) {
        try {
            popSessionRecorder.record(record)
        } catch (e: Exception) {
            android.util.Log.e("OrionPoP", "queuePopLog failed: ${e.message}", e)
            deviceLogCollector.logSync("PoP queue failed: ${e.message}")
        }
    }

    fun retry() {
        startPlayback()
    }

    override fun onCleared() {
        super.onCleared()
        recoveryCoordinator.unregisterPlaybackRestartHandler()
        contentSyncScheduler.unregisterFullSyncHandler()
        revisionPollScheduler.unregisterSyncHandler()
        contentSyncCoordinator.unregisterRetrySyncHandler()
        initialSyncCoordinator.unregisterSyncHandler()
        remoteCommandExecutor.unregisterForceSyncHandler()
        remoteCommandExecutor.unregisterScreenshotWindowProvider()
        playerEventStreamClient.stop()
        slotGeneration++
        advanceJob?.cancel()
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
