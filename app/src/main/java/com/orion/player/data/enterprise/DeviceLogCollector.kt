package com.orion.player.data.enterprise

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ring-buffer diagnostic log file for enterprise upload.
 * Filter live logs: adb logcat -s OrionEnterprise
 */
@Singleton
class DeviceLogCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Resolved lazily and defensively: this singleton can be constructed while the user is
    // still locked (a direct-boot-aware receiver starts the process), and credential-
    // protected storage does not exist yet at that point.
    private val logDir: File? by lazy {
        runCatching { File(context.filesDir, "enterprise_logs").apply { mkdirs() } }.getOrNull()
    }
    private val logFile: File? by lazy { logDir?.let { File(it, "device.log") } }
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun log(category: String, message: String) {
        val line = "${formatter.format(Date())} [$category] $message\n"
        synchronized(lock) {
            try {
                logFile?.let { file ->
                    if (file.length() > MAX_LOG_BYTES) {
                        rotateLog()
                    }
                    file.appendText(line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write log: ${e.message}")
            }
        }
        Log.i(TAG, "[$category] $message")
    }

    fun logBoot(source: String) = log("BOOT", "Device boot handled: $source")
    fun logCrash(message: String) = log("CRASH", message)
    fun logPlayback(message: String) = log("PLAYBACK", message)
    fun logDownload(message: String) = log("DOWNLOAD", message)
    fun logPop(message: String) = log("POP", message)
    fun logSync(message: String) = log("SYNC", message)
    fun logError(message: String) = log("ERROR", message)
    fun logCommand(type: String, result: String) = log("COMMAND", "$type -> $result")

    fun readLogs(): String = synchronized(lock) {
        val file = logFile ?: return ""
        if (!file.exists()) return ""
        runCatching { file.readText() }.getOrDefault("")
    }

    fun clearLogs() = synchronized(lock) {
        logFile?.delete()
        logDir?.let { File(it, "device.log.old").delete() }
        Unit
    }

    private fun rotateLog() {
        val dir = logDir ?: return
        val old = File(dir, "device.log.old")
        old.delete()
        logFile?.renameTo(old)
    }

    companion object {
        private const val TAG = "OrionEnterprise"
        private const val MAX_LOG_BYTES = 2 * 1024 * 1024L
    }
}
