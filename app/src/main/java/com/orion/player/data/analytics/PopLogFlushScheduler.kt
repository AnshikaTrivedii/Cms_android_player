package com.orion.player.data.analytics

import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.util.SessionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide PoP flush scheduler. Runs independently of [com.orion.player.ui.playback.PlaybackViewModel].
 */
@Singleton
class PopLogFlushScheduler @Inject constructor(
    private val telemetryRepository: TelemetryRepository,
    private val sessionGuard: SessionGuard,
    private val securePrefs: SecurePrefs,
    private val popConfigManager: PopConfigManager
) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 90_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (flushJob?.isActive == true) return
        PopTelemetryLogger.logSchedulerStarted(
            hardwareId = securePrefs.getOrCreateHardwareId(),
            intervalMs = intervalMs
        )
        flushJob = scope.launch {
            flushNow()
            while (true) {
                delay(intervalMs)
                flushNow()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
    }

    suspend fun flushNow(): Int {
        if (!sessionGuard.isPairedWithToken()) return 0
        if (!popConfigManager.isPopEnabled()) return 0
        return telemetryRepository.flushPopLogs()
    }
}
