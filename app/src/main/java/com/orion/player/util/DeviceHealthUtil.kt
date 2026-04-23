package com.orion.player.util

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility for reading device health metrics (CPU, RAM, temperature).
 * Used by the heartbeat service to report telemetry.
 */
@Singleton
class DeviceHealthUtil @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Returns CPU usage percentage (0-100).
     * Reads from /proc/stat. Returns -1 if unavailable.
     */
    fun getCpuUsage(): Int {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val firstLine = reader.readLine()
            reader.close()

            val parts = firstLine.split("\\s+".toRegex())
            if (parts.size < 5) return -1

            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = if (parts.size > 5) parts[5].toLong() else 0L

            val total = user + nice + system + idle + iowait
            val active = user + nice + system

            if (total == 0L) return 0
            ((active * 100) / total).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Returns RAM usage percentage (0-100).
     */
    fun getRamUsage(): Int {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val usedMem = memInfo.totalMem - memInfo.availMem
            ((usedMem * 100) / memInfo.totalMem).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Returns device temperature in °C.
     * Reads from thermal zone. Returns -1 if unavailable.
     */
    fun getTemperature(): Int {
        return try {
            // Try common thermal zone paths
            val paths = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )

            for (path in paths) {
                try {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        val temp = file.readText().trim().toLong()
                        // Temperatures are usually in millidegrees
                        return (temp / 1000).toInt().coerceIn(0, 120)
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            -1
        } catch (e: Exception) {
            -1
        }
    }
}
