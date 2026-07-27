package com.orion.player.data.sync

import android.util.Log
import com.orion.player.data.analytics.PopConfigManager
import com.orion.player.data.enterprise.RemoteCommandExecutor
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.DeviceReportResponse
import com.orion.player.data.remote.HeartbeatResponse
import com.orion.player.data.remote.SyncResponse
import com.orion.player.data.repository.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the first playlist download immediately after pairing/assignment.
 * Bypasses the normal full-sync polling timer when initial sync is pending.
 */
@Singleton
class InitialSyncCoordinator @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val syncIntervalConfig: SyncIntervalConfig,
    private val revisionPollIntervalConfig: RevisionPollIntervalConfig,
    private val popConfigManager: PopConfigManager,
    private val telemetryRepository: TelemetryRepository,
    private val remoteCommandExecutor: RemoteCommandExecutor
) {
    init {
        remoteCommandExecutor.registerForceSyncHandler { commandId, reason ->
            runForcedSync(reason, commandId)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var retryJob: Job? = null
    private var timeoutJob: Job? = null
    private var syncHandler: (suspend (reason: String, commandId: String?) -> Boolean)? = null

    @Volatile
    private var initialSyncPending: Boolean = false

    fun registerSyncHandler(handler: suspend (reason: String, commandId: String?) -> Boolean) {
        syncHandler = handler
    }

    fun unregisterSyncHandler() {
        syncHandler = null
    }

    fun isInitialSyncPending(): Boolean = initialSyncPending

    fun markInitialDownloadStarted() {
        securePrefs.initialDownloadStarted = true
    }

    fun handleHeartbeatResponse(response: HeartbeatResponse) {
        handleSignals(ServerPlayerSignals.from(response))
    }

    fun handleSyncResponse(response: SyncResponse) {
        handleSignals(ServerPlayerSignals.from(response))
        response.commands?.takeIf { it.isNotEmpty() }?.let { remoteCommandExecutor.dispatch(it) }
    }

    fun handleDeviceReportResponse(response: DeviceReportResponse) {
        handleSignals(ServerPlayerSignals.from(response))
        response.commands?.takeIf { it.isNotEmpty() }?.let { remoteCommandExecutor.dispatch(it) }
    }

    fun onPairingCompleted() {
        securePrefs.pairedAtMs = System.currentTimeMillis()
        securePrefs.initialDownloadStarted = false
        Log.i(TAG, "Pairing completed — starting initial sync watchdog")
        startTimeoutWatchdog()
        ensureRetryLoop()
    }

    private fun handleSignals(signals: ServerPlayerSignals) {
        popConfigManager.update(signals.popLogsExpected, signals.features)
        syncIntervalConfig.updateInterval(signals.syncIntervalSeconds)
        revisionPollIntervalConfig.updateInterval(signals.revisionPollIntervalSeconds)

        signals.initialSyncPending?.let { pending ->
            initialSyncPending = pending
            if (!pending) {
                stopRetryLoop()
                timeoutJob?.cancel()
            }
        }
        signals.initialSyncTimeoutSeconds?.let {
            securePrefs.initialSyncTimeoutSeconds = it.coerceIn(
                SyncIntervalConfig.MIN_SECONDS,
                SyncIntervalConfig.MAX_SECONDS
            )
        }

        val shouldSyncImmediately =
            signals.syncRequired == true ||
                signals.initialSyncPending == true

        if (shouldSyncImmediately) {
            scope.launch {
                runForcedSync(
                    reason = when {
                        signals.initialSyncPending == true -> "initial_sync.pending"
                        else -> "heartbeat.sync_required"
                    },
                    commandId = null
                )
            }
        }

        if (initialSyncPending) {
            ensureRetryLoop()
            startTimeoutWatchdog()
        }
    }

    suspend fun runForcedSync(reason: String, commandId: String? = null): Boolean =
        syncMutex.withLock {
            val handler = syncHandler
            if (handler == null) {
                Log.w(TAG, "Initial sync skipped — handler not registered ($reason)")
                return false
            }

            Log.i(TAG, "Running forced sync reason=$reason commandId=${commandId.orEmpty()}")
            val success = runCatching { handler(reason, commandId) }
                .onFailure { Log.e(TAG, "Forced sync failed: ${it.message}", it) }
                .getOrDefault(false)

            if (commandId != null) {
                telemetryRepository.submitDeviceReport(
                    completedCommandId = commandId,
                    commandFailed = !success,
                    commandError = if (success) null else "sync_failed:$reason"
                )
            }

            if (success) {
                markInitialDownloadStarted()
            }
            success
        }

    private fun ensureRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (initialSyncPending) {
                delay(RETRY_INTERVAL_MS)
                if (!initialSyncPending) break
                val success = runForcedSync(reason = "initial_sync.retry")
                if (success) break
            }
        }
    }

    private fun stopRetryLoop() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun startTimeoutWatchdog() {
        if (timeoutJob?.isActive == true) return
        val pairedAt = securePrefs.pairedAtMs
        if (pairedAt <= 0L) return

        timeoutJob = scope.launch {
            val timeoutSeconds = securePrefs.initialSyncTimeoutSeconds
                .coerceIn(SyncIntervalConfig.MIN_SECONDS, SyncIntervalConfig.MAX_SECONDS)
            val deadlineMs = pairedAt + timeoutSeconds * 1000L
            val waitMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(waitMs)

            if (!initialSyncPending) return@launch
            if (securePrefs.initialDownloadStarted) return@launch

            val elapsedSec = ((System.currentTimeMillis() - pairedAt) / 1000L).coerceAtLeast(0L)
            val message =
                "Initial playlist download has not started within ${timeoutSeconds}s (elapsed=${elapsedSec}s)"
            Log.w(TAG, message)
            telemetryRepository.submitSystemLogs(
                category = "initial_sync",
                message = message,
                metadata = mapOf(
                    "pairedAtMs" to pairedAt,
                    "timeoutSeconds" to timeoutSeconds,
                    "elapsedSeconds" to elapsedSec
                )
            )
            ensureRetryLoop()
        }
    }

    companion object {
        private const val TAG = "OrionInitialSync"
        private const val RETRY_INTERVAL_MS = 10_000L
    }
}
