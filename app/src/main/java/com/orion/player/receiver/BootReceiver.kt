package com.orion.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orion.player.MainActivity

/**
 * Broadcast receiver that automatically launches the Orion Player on device boot.
 * Required for kiosk-mode digital signage operation.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}
