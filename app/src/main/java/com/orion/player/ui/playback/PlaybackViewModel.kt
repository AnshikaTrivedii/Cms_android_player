package com.orion.player.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.PlaylistInfo
import com.orion.player.data.repository.ContentRepository
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.util.DeviceHealthUtil
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
 * - Sync playlist & download assets
 * - Advance through assets on a timer
 * - Send heartbeats every 60s
 * - Queue & flush PoP logs every 5 min
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val telemetryRepository: TelemetryRepository,
    private val deviceHealthUtil: DeviceHealthUtil,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _currentAssetIndex = MutableStateFlow(0)
    val currentAssetIndex: StateFlow<Int> = _currentAssetIndex.asStateFlow()

    private var assets: List<AssetInfo> = emptyList()
    private var localFiles: Map<String, File> = emptyMap()
    private var playlistInfo: PlaylistInfo? = null

    private var advanceJob: Job? = null
    private var syncJob: Job? = null
    private var heartbeatJob: Job? = null
    private var popFlushJob: Job? = null

    // Track if we received a 401 (device unpaired/revoked)
    private val _isUnpaired = MutableStateFlow(false)
    val isUnpaired: StateFlow<Boolean> = _isUnpaired.asStateFlow()

    init {
        startPlayback()
        startHeartbeatLoop()
        startPopFlushLoop()
        startPeriodicSync()
    }

    private fun startPlayback() {
        viewModelScope.launch {
            try {
                _uiState.value = PlaybackUiState.Loading

                val syncResponse = contentRepository.syncPlaylist()

                if (syncResponse == null) {
                    _isUnpaired.value = true
                    return@launch
                }

                if (syncResponse.playlist == null || syncResponse.assets.isEmpty()) {
                    _uiState.value = PlaybackUiState.NoContent
                    return@launch
                }

                playlistInfo = syncResponse.playlist
                assets = syncResponse.assets.sortedBy { it.position }

                // Download all assets
                _uiState.value = PlaybackUiState.Downloading(0, assets.size)

                localFiles = contentRepository.downloadAllAssets(assets)

                // Clean up old cached files
                contentRepository.cleanupStaleCache(assets.map { it.id }.toSet())

                if (localFiles.isEmpty()) {
                    _uiState.value = PlaybackUiState.Error("Failed to download content")
                    return@launch
                }

                // Start playback from first asset
                _currentAssetIndex.value = 0
                emitCurrentAsset()
                startAssetAdvancement()

            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) {
                    securePrefs.clearCredentials()
                    _isUnpaired.value = true
                } else {
                    handleSyncError(e)
                }
            } catch (e: Exception) {
                handleSyncError(e)
            }
        }
    }

    private fun handleSyncError(e: Exception) {
        // If we have cached content, keep playing it (offline mode)
        if (assets.isNotEmpty() && localFiles.isNotEmpty()) {
            // Already playing — just log the error
            e.printStackTrace()
        } else {
            _uiState.value = PlaybackUiState.Error(
                e.message ?: "Failed to sync content"
            )
        }
    }

    /**
     * Advances to the next asset after the current one's duration expires.
     */
    private fun startAssetAdvancement() {
        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            while (true) {
                val index = _currentAssetIndex.value
                if (index >= assets.size) break

                val asset = assets[index]

                // Log PoP entry
                queuePopLog(asset.name, "VERIFIED")

                // Wait for asset duration
                delay(asset.durationSeconds * 1000L)

                // Advance to next asset (loop back to 0 at end)
                val nextIndex = (index + 1) % assets.size
                _currentAssetIndex.value = nextIndex
                emitCurrentAsset()
            }
        }
    }

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
                playlistName = playlistInfo?.name ?: ""
            )
        }
    }

    /**
     * Called by VideoPlayer when video finishes (may exceed durationSeconds).
     * Immediately advances to the next asset.
     */
    fun onVideoCompleted() {
        advanceJob?.cancel()
        val nextIndex = (_currentAssetIndex.value + 1) % assets.size
        _currentAssetIndex.value = nextIndex
        emitCurrentAsset()
        startAssetAdvancement()
    }

    /**
     * Report a failed asset playback.
     */
    fun onAssetFailed(assetName: String) {
        viewModelScope.launch {
            queuePopLog(assetName, "FAILED")
        }
        // Skip to next asset
        onVideoCompleted()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(60_000L) // Every 60 seconds
                try {
                    val currentAsset = if (_currentAssetIndex.value < assets.size) {
                        assets[_currentAssetIndex.value].name
                    } else null

                    telemetryRepository.sendHeartbeat(
                        cpu = deviceHealthUtil.getCpuUsage().coerceAtLeast(0),
                        ram = deviceHealthUtil.getRamUsage().coerceAtLeast(0),
                        temp = deviceHealthUtil.getTemperature().coerceAtLeast(0),
                        currentContent = currentAsset
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
                delay(300_000L) // Every 5 minutes
                try {
                    telemetryRepository.flushPopLogs()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                delay(300_000L) // Every 5 minutes
                try {
                    val syncResponse = contentRepository.syncPlaylist() ?: continue

                    if (syncResponse.playlist == null || syncResponse.assets.isEmpty()) {
                        continue
                    }

                    val newAssets = syncResponse.assets.sortedBy { it.position }

                    // Check if playlist has changed
                    val currentIds = assets.map { it.id }.toSet()
                    val newIds = newAssets.map { it.id }.toSet()

                    if (currentIds != newIds || syncResponse.playlist != playlistInfo) {
                        // Download any new assets
                        val newFiles = contentRepository.downloadAllAssets(newAssets)
                        contentRepository.cleanupStaleCache(newIds)

                        // Update state
                        assets = newAssets
                        localFiles = localFiles + newFiles
                        playlistInfo = syncResponse.playlist

                        // Restart from beginning if playlist structure changed
                        _currentAssetIndex.value = 0
                        emitCurrentAsset()
                        startAssetAdvancement()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun queuePopLog(content: String, status: String) {
        try {
            telemetryRepository.queuePopLog(
                content = content,
                status = status,
                timestamp = Instant.now().toString()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun retry() {
        startPlayback()
    }

    override fun onCleared() {
        super.onCleared()
        advanceJob?.cancel()
        syncJob?.cancel()
        heartbeatJob?.cancel()
        popFlushJob?.cancel()
    }
}

/**
 * Sealed class representing all possible playback screen states.
 */
sealed class PlaybackUiState {
    data object Loading : PlaybackUiState()
    data class Downloading(val current: Int, val total: Int) : PlaybackUiState()
    data object NoContent : PlaybackUiState()
    data class Playing(
        val asset: AssetInfo,
        val localFile: File?,
        val currentIndex: Int,
        val totalAssets: Int,
        val playlistName: String
    ) : PlaybackUiState()
    data class Error(val message: String) : PlaybackUiState()
}
