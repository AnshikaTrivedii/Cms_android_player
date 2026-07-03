package com.orion.player.data.recovery

import android.content.Context
import android.content.Intent
import com.orion.player.MainActivity
import com.orion.player.service.PlayerForegroundService

/**
 * Central entry for launching the player from boot, crash recovery, or watchdog.
 */
object PlayerLaunchHelper {

    const val EXTRA_LAUNCH_SOURCE = "com.orion.player.extra.LAUNCH_SOURCE"

    fun launchFromBoot(context: Context, bootAction: String) {
        OrionRecoveryLogger.logDeviceBootDetected(bootAction)
        OrionRecoveryLogger.logBootReceiverTriggered(bootAction)
        startProtection(context)
        launchPlayer(context, "boot.$bootAction")
    }

    fun startProtection(context: Context) {
        PlayerForegroundService.start(context)
    }

    fun launchPlayer(context: Context, source: String) {
        startProtection(context)
        OrionRecoveryLogger.logPlayerStarted(source)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_LAUNCH_SOURCE, source)
        }
        context.startActivity(launchIntent)
    }
}
