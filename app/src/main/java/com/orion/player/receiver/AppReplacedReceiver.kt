package com.orion.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.orion.player.data.recovery.PlayerLaunchHelper
import com.orion.player.data.update.AppUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AppReplacedReceiver : BroadcastReceiver() {
    @Inject lateinit var updates: AppUpdateManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        updates.onPackageReplaced()
        PlayerLaunchHelper.launchPlayer(context, "app_update.package_replaced")
    }
}
