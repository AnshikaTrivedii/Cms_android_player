package com.orion.player.data.sync

import android.util.Log
import com.orion.player.data.repository.CacheReportRepository
import com.orion.player.util.SessionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loop A: lightweight GET /player/sync-revision every revisionPollIntervalSeconds (default 5s).
 * Primary change-detection path — triggers full sync immediately when revision or assignment changes.
 * Compares the full contentRevision string (including the :tk… ticker suffix).
 */
@Singleton
class RevisionPollScheduler @Inject constructor(
    private val contentSyncCoordinator: ContentSyncCoordinator,
    private val revisionPollIntervalConfig: RevisionPollIntervalConfig,
    private val sessionGuard: SessionGuard
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var configObserverJob: Job? = null
    private var syncHandler: (suspend (reason: String) -> Boolean)? = null

    fun registerSyncHandler(handler: suspend (reason: String) -> Boolean) {
        syncHandler = handler
    }

    fun unregisterSyncHandler() {
        syncHandler = null
    }

    fun start() {
        if (pollJob?.isActive == true) return
        Log.i(TAG, "Revision poll scheduler started intervalSec=${revisionPollIntervalConfig.intervalSeconds.value}")
        configObserverJob?.cancel()
        configObserverJob = scope.launch {
            revisionPollIntervalConfig.intervalSeconds.drop(1).collect { restartPollLoop() }
        }
        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        configObserverJob?.cancel()
        configObserverJob = null
        pollJob?.cancel()
        pollJob = null
    }

    private fun restartPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (true) {
            delay(revisionPollIntervalConfig.intervalMs())
            if (!sessionGuard.isPairedWithToken()) continue
            val outcome = contentSyncCoordinator.evaluateRevisionPoll()
            if (outcome.shouldSync) {
                val reason = outcome.reason ?: "revision.poll"
                Log.i(TAG, "revision_poll trigger reason=$reason")
                syncHandler?.invoke(reason)
            }
        }
    }

    companion object {
        private const val TAG = "OrionSync"
    }
}

data class RevisionPollOutcome(
    val shouldSync: Boolean,
    val reason: String? = null
)
