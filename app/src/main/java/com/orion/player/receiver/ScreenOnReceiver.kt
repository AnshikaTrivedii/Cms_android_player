package com.orion.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orion.player.data.recovery.AutoStartCoordinator
import com.orion.player.data.recovery.PlayerLaunchHelper

/**
 * Brings the player forward when the display wakes without a reboot.
 *
 * [Intent.ACTION_SCREEN_ON] and [Intent.ACTION_USER_PRESENT] cannot be declared in the
 * manifest on modern Android, so this receiver is registered dynamically by
 * [com.orion.player.service.PlayerForegroundService]. A launch still needs the Home
 * role (or device owner) on Android 10+; this is the safety net when an OEM shows
 * its own splash after HDMI / power-on.
 */
class ScreenOnReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) return
        val appContext = context.applicationContext
        if (AutoStartCoordinator.isPlayerInForeground(appContext)) return
        PlayerLaunchHelper.launchPlayer(appContext, "screen.on")
    }
}
