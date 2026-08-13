package com.orion.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.orion.player.data.recovery.PlayerLaunchHelper

/**
 * Alarm target used by [com.orion.player.data.recovery.CrashRecovery] to bring the player
 * back after a crash-loop backoff, once the crashing process is gone.
 *
 * Not exported: only this app's own alarm can trigger it.
 */
class RelaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELAYED_RELAUNCH) return
        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) return
        PlayerLaunchHelper.launchAfterCrashBackoff(appContext)
    }

    companion object {
        const val ACTION_DELAYED_RELAUNCH = "com.orion.player.action.DELAYED_RELAUNCH"
    }
}
