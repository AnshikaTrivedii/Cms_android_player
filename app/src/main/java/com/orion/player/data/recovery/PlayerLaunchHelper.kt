package com.orion.player.data.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.orion.player.receiver.RelaunchReceiver
import com.orion.player.service.PlayerForegroundService

/**
 * Central entry for launching the player from boot, crash recovery, or watchdog.
 *
 * Kept as the stable façade used across the app; the launch itself is delegated to
 * [AutoStartCoordinator] so that duplicate-launch protection and auto-launch logging are
 * applied uniformly to every caller.
 */
object PlayerLaunchHelper {

    const val EXTRA_LAUNCH_SOURCE = AutoStartCoordinator.EXTRA_LAUNCH_SOURCE

    fun launchFromBoot(context: Context, bootAction: String) {
        OrionRecoveryLogger.logDeviceBootDetected(bootAction)
        OrionRecoveryLogger.logBootReceiverTriggered(bootAction)
        // The foreground service owns the retry/backoff loop: components are not
        // necessarily ready the instant BOOT_COMPLETED arrives, and some platforms drop
        // the first background activity start.
        BootStateStore.from(context).markBootLaunchPending()
        AutoStartCoordinator.logLaunchPrivileges(context)
        startProtection(context)
        launchPlayer(context, "boot.$bootAction")
    }

    fun startProtection(context: Context) {
        PlayerForegroundService.start(context)
    }

    fun launchPlayer(context: Context, source: String, attempt: Int = 1): Boolean {
        startProtection(context)
        OrionRecoveryLogger.logPlayerStarted(source)
        return AutoStartCoordinator.launch(context, source, attempt)
    }

    /** Relaunch attempt scheduled by [CrashRecovery] after a crash-loop backoff. */
    fun launchAfterCrashBackoff(context: Context) {
        startProtection(context)
        AutoStartCoordinator.launch(context, "crash.backoff_retry")
    }

    /**
     * Schedule a relaunch after the current process is killed. Used when an immediate
     * relaunch would create a crash loop. Inexact on purpose: no exact-alarm permission is
     * needed and a signage device is mains-powered with a wake lock held by the service.
     */
    fun scheduleDelayedRelaunch(context: Context, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, RelaunchReceiver::class.java).apply {
            action = RelaunchReceiver.ACTION_DELAYED_RELAUNCH
            setPackage(context.packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, RELAUNCH_REQUEST_CODE, intent, flags)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private const val RELAUNCH_REQUEST_CODE = 4101
}
