package com.orion.player.data.enterprise

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.orion.player.BuildConfig
import com.orion.player.data.cache.ContentCacheManager
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.util.DeviceHealthUtil
import com.orion.player.util.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceHealthReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceHealthUtil: DeviceHealthUtil,
    private val contentCacheManager: ContentCacheManager,
    private val networkMonitor: NetworkMonitor,
    private val healthMonitor: PlayerHealthMonitor
) {
    fun snapshot(
        playlistName: String?,
        currentAsset: String?,
        queueSize: Int
    ): DeviceHealthSnapshot {
        val (storageFreeMb, storageTotalMb) = readStorageMb()
        val cacheStats = contentCacheManager.getCacheStats()
        val battery = readBattery()
        val playbackStatus = when {
            healthMonitor.isPlaybackActive -> "playing"
            healthMonitor.isPlaybackExpected -> "starting"
            else -> "idle"
        }
        return DeviceHealthSnapshot(
            cpuPercent = deviceHealthUtil.getCpuUsage().coerceAtLeast(0),
            ramPercent = deviceHealthUtil.getRamUsage().coerceAtLeast(0),
            temperatureCelsius = deviceHealthUtil.getTemperature().coerceAtLeast(0),
            storageFreeMb = storageFreeMb,
            storageTotalMb = storageTotalMb,
            cacheSizeMb = cacheStats.totalSizeBytes / (1024 * 1024),
            cacheFileCount = cacheStats.fileCount,
            batteryPercent = battery?.first,
            batteryCharging = battery?.second,
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000L,
            appVersion = BuildConfig.VERSION_NAME,
            playbackStatus = playbackStatus,
            currentPlaylist = playlistName,
            currentAsset = currentAsset,
            queueSize = queueSize,
            networkOnline = networkMonitor.isOnline,
            threadCount = Thread.activeCount()
        )
    }

    private fun readStorageMb(): Pair<Long, Long> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val total = stat.blockCountLong * blockSize / (1024 * 1024)
            val free = stat.availableBlocksLong * blockSize / (1024 * 1024)
            free to total
        } catch (_: Exception) {
            0L to 0L
        }
    }

    private fun readBattery(): Pair<Int, Boolean>? {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter) ?: return null
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (percent < 0) null else percent to charging
        } catch (_: Exception) {
            null
        }
    }
}
