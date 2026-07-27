package com.orion.player.data.enterprise

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import com.orion.player.data.cache.ContentCacheManager
import com.orion.player.data.recovery.PlayerLaunchHelper
import com.orion.player.data.repository.TelemetryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes remote commands received from CMS heartbeat/sync responses.
 */
@Singleton
class RemoteCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: DeviceLogCollector,
    private val contentCacheManager: ContentCacheManager,
    private val telemetryRepository: TelemetryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var forceSyncHandler: (suspend (commandId: String?, reason: String) -> Boolean)? = null
    @Volatile private var screenshotWindowProvider: (() -> Window?)? = null

    fun registerForceSyncHandler(handler: suspend (commandId: String?, reason: String) -> Boolean) {
        forceSyncHandler = handler
    }

    fun unregisterForceSyncHandler() {
        forceSyncHandler = null
    }

    fun registerScreenshotWindowProvider(provider: () -> Window?) {
        screenshotWindowProvider = provider
    }

    fun unregisterScreenshotWindowProvider() {
        screenshotWindowProvider = null
    }

    fun dispatch(commands: List<RemoteCommand>) {
        if (commands.isEmpty()) return
        scope.launch {
            for (command in commands) {
                execute(command)
            }
        }
    }

    private suspend fun execute(command: RemoteCommand) {
        val type = command.type.trim().lowercase(Locale.US).replace('-', '_')
        logCollector.logCommand(type, "received id=${command.id}")
        val result = when (type) {
            RemoteCommandType.RESTART_PLAYER -> restartPlayer()
            RemoteCommandType.RESTART_DEVICE -> restartDevice()
            RemoteCommandType.FORCE_SYNC -> forceSync(command)
            RemoteCommandType.CLEAR_CACHE -> clearCache()
            RemoteCommandType.REDOWNLOAD_PLAYLIST -> redownloadPlaylist()
            RemoteCommandType.UPLOAD_LOGS -> uploadLogs()
            RemoteCommandType.TAKE_SCREENSHOT -> takeScreenshot()
            else -> "unsupported:$type"
        }
        logCollector.logCommand(type, result)
    }

    private fun restartPlayer(): String {
        PlayerLaunchHelper.launchPlayer(context, "remote.restart_player")
        return "restarted"
    }

    private fun restartDevice(): String {
        return runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            "reboot_requested_su"
        }.getOrElse {
            runCatching {
                val intent = android.content.Intent("android.intent.action.REBOOT")
                intent.putExtra("nowait", 1)
                intent.putExtra("interval", 1)
                intent.putExtra("window", 0)
                context.sendBroadcast(intent)
                "reboot_broadcast_sent"
            }.getOrElse { e ->
                "unsupported:${e.message}"
            }
        }
    }

    private suspend fun forceSync(command: RemoteCommand): String {
        val handler = forceSyncHandler
        return if (handler != null) {
            val success = handler(command.id, "remote.force_sync")
            if (success) "sync_completed" else "sync_failed"
        } else {
            "sync_handler_unavailable"
        }
    }

    private suspend fun clearCache(): String {
        val dir = contentCacheManager.getContentDirectory()
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) deleted++
        }
        logCollector.logSync("Cache cleared by remote command: deleted=$deleted files")
        forceSyncHandler?.let { handler ->
            scope.launch { handler(null, "remote.clear_cache") }
        }
        return "cleared_files=$deleted"
    }

    private suspend fun redownloadPlaylist(): String {
        forceSyncHandler?.let { handler ->
            handler(null, "remote.redownload_playlist")
        }
        return "redownload_triggered"
    }

    private suspend fun uploadLogs(): String {
        val uploaded = telemetryRepository.uploadDeviceLogs()
        return if (uploaded) "uploaded" else "upload_failed"
    }

    private suspend fun takeScreenshot(): String {
        val window = screenshotWindowProvider?.invoke()
            ?: return "no_window"
        val bitmap = captureWindow(window) ?: return "capture_failed"
        val dir = File(context.filesDir, "enterprise_logs/screenshots")
        dir.mkdirs()
        val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        bitmap.recycle()
        logCollector.log("SCREENSHOT", "Saved ${file.absolutePath}")
        telemetryRepository.uploadDeviceLogs(screenshotPath = file.absolutePath)
        return "saved:${file.name}"
    }

    private suspend fun captureWindow(window: Window): Bitmap? {
        val view = window.decorView.rootView
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val success = suspendCancellableCoroutine { cont ->
            PixelCopy.request(
                window,
                bitmap,
                { result -> cont.resume(result == PixelCopy.SUCCESS) },
                mainHandler
            )
        }
        return if (success) bitmap else {
            bitmap.recycle()
            null
        }
    }
}
