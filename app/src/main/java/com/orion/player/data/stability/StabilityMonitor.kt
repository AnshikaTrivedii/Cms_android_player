package com.orion.player.data.stability

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.orion.player.data.analytics.PopTelemetryLogger
import com.orion.player.data.cache.ContentCacheManager
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.recovery.PlayerRuntimeConfig
import com.orion.player.data.repository.TelemetryRepository
import com.orion.player.util.DeviceHealthUtil
import com.orion.player.util.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic 24x7 stability snapshots for production diagnostics.
 * Filter: adb logcat -s OrionStability
 */
@Singleton
class StabilityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthMonitor: PlayerHealthMonitor,
    private val telemetryRepository: TelemetryRepository,
    private val deviceHealthUtil: DeviceHealthUtil,
    private val networkMonitor: NetworkMonitor,
    private val contentCacheManager: ContentCacheManager
) {
    private var lastReportMs = 0L

    suspend fun reportIfDue(
        queueSize: Int,
        currentAsset: String?,
        playlistName: String?
    ) {
        val now = System.currentTimeMillis()
        if (now - lastReportMs < PlayerRuntimeConfig.STABILITY_REPORT_INTERVAL_MS) return
        lastReportMs = now

        val pendingPop = telemetryRepository.getUnsyncedPopCount()
        PopTelemetryLogger.logQueueStatus(pendingPop)

        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxHeapMb = runtime.maxMemory() / (1024 * 1024)
        val threadCount = Thread.activeCount()
        val cpu = deviceHealthUtil.getCpuUsage()
        val ram = deviceHealthUtil.getRamUsage()
        val cacheStats = contentCacheManager.getCacheStats()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        Log.i(
            TAG,
            "Stability report: heapUsedMb=$usedHeapMb heapMaxMb=$maxHeapMb " +
                "deviceRamUsedPct=$ram lowMemory=${memInfo.lowMemory} " +
                "cpuPct=$cpu threads=$threadCount " +
                "queueSize=$queueSize currentAsset=${currentAsset.orEmpty()} " +
                "playlist=${playlistName.orEmpty()} " +
                "popPending=$pendingPop " +
                "cacheFiles=${cacheStats.fileCount} cacheMb=${cacheStats.totalSizeBytes / (1024 * 1024)} " +
                "networkOnline=${networkMonitor.isOnline} " +
                "activityAlive=${healthMonitor.isActivityAlive} " +
                "playbackActive=${healthMonitor.isPlaybackActive} " +
                "slotLoopAlive=${healthMonitor.isSlotLoopAlive()} " +
                "videoRendererAlive=${healthMonitor.isVideoRendererAlive}"
        )
    }

    companion object {
        private const val TAG = "OrionStability"
    }
}
