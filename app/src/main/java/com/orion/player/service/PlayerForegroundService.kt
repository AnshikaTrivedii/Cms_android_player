package com.orion.player.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.orion.player.MainActivity
import com.orion.player.R
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.AutoStartCoordinator
import com.orion.player.data.recovery.AutoStartLogger
import com.orion.player.data.recovery.BootStateStore
import com.orion.player.data.recovery.KioskController
import com.orion.player.data.recovery.OrionRecoveryLogger
import com.orion.player.receiver.ScreenOnReceiver
import com.orion.player.data.recovery.PlaybackRecoveryCoordinator
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.recovery.PlayerLaunchHelper
import com.orion.player.data.recovery.PlayerRuntimeConfig
import com.orion.player.data.recovery.RecoveryThrottle
import com.orion.player.data.stability.StabilityMonitor
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
    private val throttle: RecoveryThrottle by lazy { RecoveryThrottle.from(applicationContext) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogStarted = false
    private var healthCheckCount = 0
    private val screenOnReceiver = ScreenOnReceiver()
    private var screenOnReceiverRegistered = false

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            performWatchdogCheck()
            handler.postDelayed(this, PlayerRuntimeConfig.WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * Boot startup is not instantaneous and the first background activity start after boot
     * can be dropped by the platform, so the launch is retried on a widening schedule until
     * the Activity is genuinely on screen.
     */
    private val bootLaunchRunnable = object : Runnable {
        override fun run() {
            val bootState = BootStateStore.from(applicationContext)
            if (!bootState.bootLaunchPending) return

            if (AutoStartCoordinator.isPlayerInForeground(applicationContext)) {
                bootState.clearBootLaunchPending()
                startForeground(NOTIFICATION_ID, buildNotification())
                return
            }

            val attempt = bootState.recordBootLaunchAttempt()
            if (attempt > PlayerRuntimeConfig.BOOT_LAUNCH_BACKOFF_MS.size) {
                bootState.clearBootLaunchPending()
                AutoStartCoordinator.logLaunchPrivileges(applicationContext)
                AutoStartLogger.autoLaunchBlocked(
                    attempts = attempt - 1,
                    defaultHome = AutoStartCoordinator.isDefaultHomeApp(applicationContext),
                    deviceOwner = KioskController.isDeviceOwner(applicationContext)
                )
                startForeground(NOTIFICATION_ID, buildNotification())
                return
            }

            PlayerLaunchHelper.launchPlayer(
                applicationContext,
                "boot.retry",
                attempt = attempt + 1
            )
            startForeground(NOTIFICATION_ID, buildNotification())
            handler.postDelayed(this, PlayerRuntimeConfig.BOOT_LAUNCH_BACKOFF_MS[attempt - 1])
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OrionRecoveryLogger.logForegroundServiceStarted()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        registerScreenOnReceiver()
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

        scheduleBootLaunchRetryIfPending()

        if (securePrefs.isAuthenticated()) {
            heartbeatScheduler.start()
            popLogFlushScheduler.start()
            contentSyncScheduler.start()
            revisionPollScheduler.start()
        }

        return START_STICKY
    }

    private fun scheduleBootLaunchRetryIfPending() {
        handler.removeCallbacks(bootLaunchRunnable)
        val bootState = BootStateStore.from(applicationContext)
        if (!bootState.bootLaunchPending) return
        val attempt = bootState.bootLaunchAttempts
            .coerceAtMost(PlayerRuntimeConfig.BOOT_LAUNCH_BACKOFF_MS.size - 1)
        handler.postDelayed(
            bootLaunchRunnable,
            PlayerRuntimeConfig.BOOT_LAUNCH_BACKOFF_MS[attempt]
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthCheckRunnable)
        handler.removeCallbacks(bootLaunchRunnable)
        unregisterScreenOnReceiver()
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
            recoverActivity()
            return
        }
        throttle.reset(RecoveryThrottle.KEY_ACTIVITY_RESTART)

        if (healthMonitor.isPlaybackStuck()) {
            recoverPlayback()
        } else {
            throttle.reset(RecoveryThrottle.KEY_PLAYBACK_RESTART)
        }
    }

    private fun recoverActivity() {
        val decision = throttle.evaluate(RecoveryThrottle.KEY_ACTIVITY_RESTART)
        if (!decision.allowed) {
            AutoStartLogger.watchdogRecoveryDeferred(
                reason = "activity_not_alive",
                retryInMs = decision.retryInMs,
                attempts = decision.attempt
            )
            return
        }
        AutoStartLogger.watchdogRecovery("activity_not_alive", decision.attempt)
        OrionRecoveryLogger.logActivityRestart("watchdog.activity_not_alive")
        PlayerLaunchHelper.launchPlayer(applicationContext, "watchdog.activity_restart")
    }

    private fun recoverPlayback() {
        val reason = when {
            healthMonitor.isPopGenerationStalled() -> "watchdog.pop_stall"
            !healthMonitor.isSlotLoopAlive() -> "watchdog.slot_loop_dead"
            else -> "watchdog.playback_stuck"
        }
        val decision = throttle.evaluate(RecoveryThrottle.KEY_PLAYBACK_RESTART)
        if (!decision.allowed) {
            AutoStartLogger.watchdogRecoveryDeferred(
                reason = reason,
                retryInMs = decision.retryInMs,
                attempts = decision.attempt
            )
            return
        }

        AutoStartLogger.watchdogRecovery(reason, decision.attempt)
        OrionRecoveryLogger.logPlaybackRestart(reason)
        if (!recoveryCoordinator.requestPlaybackRestart(reason)) {
            PlayerLaunchHelper.launchPlayer(
                applicationContext,
                "watchdog.playback_restart_fallback"
            )
        }
        // Ensure flush/heartbeat survive long runs even if jobs were cancelled.
        if (securePrefs.isAuthenticated()) {
            heartbeatScheduler.start()
            popLogFlushScheduler.start()
        }
    }

    private fun registerScreenOnReceiver() {
        if (screenOnReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenOnReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOnReceiverRegistered = true
    }

    private fun unregisterScreenOnReceiver() {
        if (!screenOnReceiverRegistered) return
        runCatching { unregisterReceiver(screenOnReceiver) }
        screenOnReceiverRegistered = false
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

        val bootPending = BootStateStore.from(this).bootLaunchPending
        val pendingIntent = createLaunchPendingIntent()
        val channelId = if (bootPending) CHANNEL_BOOT_ID else CHANNEL_ID
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(
                if (bootPending) {
                    getString(R.string.foreground_service_boot_text)
                } else {
                    getString(R.string.foreground_service_text)
                }
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (bootPending) {
            builder.setCategory(Notification.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
        } else {
            builder.setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }

        return builder.build()
    }

    private fun createLaunchPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PlayerLaunchHelper.EXTRA_LAUNCH_SOURCE, "notification.fullscreen")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 34) {
            val withOptions = runCatching {
                val options = ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                PendingIntent.getActivity(this, 0, openIntent, flags, options.toBundle())
            }
            withOptions.getOrElse {
                PendingIntent.getActivity(this, 0, openIntent, flags)
            }
        } else {
            PendingIntent.getActivity(this, 0, openIntent, flags)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.foreground_service_channel_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BOOT_ID,
                getString(R.string.foreground_service_boot_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.foreground_service_boot_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "orion_player_foreground"
        private const val CHANNEL_BOOT_ID = "orion_player_boot_launch"
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
