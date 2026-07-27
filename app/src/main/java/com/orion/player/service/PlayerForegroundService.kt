package com.orion.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.orion.player.MainActivity
import com.orion.player.R
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.OrionRecoveryLogger
import com.orion.player.data.recovery.PlaybackRecoveryCoordinator
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.recovery.PlayerLaunchHelper
import com.orion.player.data.recovery.PlayerRuntimeConfig
import com.orion.player.data.stability.StabilityMonitor
import com.orion.player.data.analytics.PopConfigManager
import com.orion.player.data.analytics.PopLogFlushScheduler
import com.orion.player.data.sync.ContentSyncScheduler
import com.orion.player.data.sync.RevisionPollScheduler
import com.orion.player.data.telemetry.DeviceHeartbeatScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Production foreground service for 24x7 digital signage.
 *
 * Responsibilities:
 * - Keeps the process alive (protects playback, sync, PoP, cache, downloads)
 * - Runs an internal watchdog every 30 seconds
 * - Restarts activity or playback on failure without rebooting the device
 */
@AndroidEntryPoint
class PlayerForegroundService : Service() {

    @Inject lateinit var healthMonitor: PlayerHealthMonitor
    @Inject lateinit var recoveryCoordinator: PlaybackRecoveryCoordinator
    @Inject lateinit var securePrefs: SecurePrefs
    @Inject lateinit var stabilityMonitor: StabilityMonitor
    @Inject lateinit var heartbeatScheduler: DeviceHeartbeatScheduler
    @Inject lateinit var popLogFlushScheduler: PopLogFlushScheduler
    @Inject lateinit var contentSyncScheduler: ContentSyncScheduler
    @Inject lateinit var revisionPollScheduler: RevisionPollScheduler

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogStarted = false
    private var healthCheckCount = 0

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            performWatchdogCheck()
            handler.postDelayed(this, PlayerRuntimeConfig.WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OrionRecoveryLogger.logForegroundServiceStarted()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        if (!watchdogStarted) {
            watchdogStarted = true
            OrionRecoveryLogger.logWatchdogStarted()
        }

        handler.removeCallbacks(healthCheckRunnable)
        handler.post(healthCheckRunnable)

        if (securePrefs.isAuthenticated()) {
            heartbeatScheduler.start()
            popLogFlushScheduler.start()
            contentSyncScheduler.start()
            revisionPollScheduler.start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthCheckRunnable)
        releaseWakeLock()
        val shouldRestart = securePrefs.isAuthenticated()
        super.onDestroy()
        if (shouldRestart) {
            OrionRecoveryLogger.logForegroundServiceRestart("onDestroy")
            start(applicationContext)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (securePrefs.isAuthenticated()) {
            PlayerLaunchHelper.launchPlayer(applicationContext, "foreground.task_removed")
        }
        start(applicationContext)
    }

    private fun performWatchdogCheck() {
        healthCheckCount++
        if (healthCheckCount % PlayerRuntimeConfig.MEMORY_LOG_EVERY_N_CHECKS == 0) {
            OrionRecoveryLogger.logMemoryStatus(
                usedMb = healthMonitor.usedMemoryMb(),
                maxMb = healthMonitor.maxMemoryMb()
            )
            serviceScope.launch {
                stabilityMonitor.reportIfDue(
                    queueSize = healthMonitor.currentQueueSize,
                    currentAsset = healthMonitor.currentAssetName,
                    playlistName = healthMonitor.currentPlaylistName
                )
            }
        }

        if (!securePrefs.isAuthenticated()) return

        OrionRecoveryLogger.logWatchdogCheck(
            activityAlive = healthMonitor.isActivityAlive,
            playbackActive = healthMonitor.isPlaybackActive,
            playbackExpected = healthMonitor.isPlaybackExpected,
            videoRendererAlive = healthMonitor.isVideoRendererAlive,
            queueProgressAgeMs = healthMonitor.queueProgressAgeMs(),
            slotLoopAlive = healthMonitor.isSlotLoopAlive(),
            lastPulseAgeMs = healthMonitor.lastPulseAgeMs()
        )

        if (!healthMonitor.isActivityAlive) {
            OrionRecoveryLogger.logActivityRestart("watchdog.activity_not_alive")
            PlayerLaunchHelper.launchPlayer(applicationContext, "watchdog.activity_restart")
            return
        }

        if (healthMonitor.isPlaybackStuck()) {
            OrionRecoveryLogger.logPlaybackRestart("watchdog.playback_stuck")
            if (!recoveryCoordinator.requestPlaybackRestart("watchdog.playback_stuck")) {
                PlayerLaunchHelper.launchPlayer(
                    applicationContext,
                    "watchdog.playback_restart_fallback"
                )
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OrionPlayer::ForegroundPlayback"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.foreground_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.foreground_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "orion_player_foreground"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, PlayerForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                OrionRecoveryLogger.logCrashDetected(e)
            }
        }
    }
}
