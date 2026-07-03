package com.orion.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orion.player.data.enterprise.EnterpriseEntryPoint
import com.orion.player.data.recovery.PlayerLaunchHelper
import dagger.hilt.android.EntryPointAccessors

/**
 * Automatically launches Orion Player after device boot, power restore, or OEM quick-boot.
 *
 * Paired devices go directly to playback (MainActivity routes to PLAYBACK screen).
 * Unpaired devices show the pairing screen.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                EnterpriseEntryPoint::class.java
            ).deviceLogCollector().logBoot(action)
        }
        PlayerLaunchHelper.launchFromBoot(context.applicationContext, action)
    }

    companion object {
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT"
        )
    }
}
