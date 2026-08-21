package com.orion.player.data.sync

import android.util.Log
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
 * Schedules periodic full [GET /player/sync] polls using the server-configured interval.
 * Lightweight [GET /player/sync-revision] polling is off by default (interval 0).
 */
@Singleton
class ContentSyncScheduler @Inject constructor(
    private val syncIntervalConfig: SyncIntervalConfig,
    private val sessionGuard: SessionGuard
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var configObserverJob: Job? = null
    private var fullSyncHandler: ((reason: String) -> Unit)? = null

    fun registerFullSyncHandler(handler: (reason: String) -> Unit) {
        fullSyncHandler = handler
    }

    fun unregisterFullSyncHandler() {
        fullSyncHandler = null
    }

    fun start() {
        if (pollJob?.isActive == true) return
        Log.i(TAG, "Full sync scheduler started intervalSec=${syncIntervalConfig.intervalSeconds.value}")
        configObserverJob?.cancel()
        configObserverJob = scope.launch {
            syncIntervalConfig.intervalSeconds.drop(1).collect { restartPollLoop() }
        }
        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        configObserverJob?.cancel()
        configObserverJob = null
        pollJob?.cancel()
        pollJob = null
    }

    fun updateInterval(seconds: Int?) {
        if (syncIntervalConfig.updateInterval(seconds)) {
            restartPollLoop()
        }
    }

    private fun restartPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (true) {
            delay(syncIntervalConfig.intervalMs())
            if (!sessionGuard.isPairedWithToken()) continue
            fullSyncHandler?.invoke("full.poll")
        }
    }

    companion object {
        private const val TAG = "OrionSync"
    }
}
