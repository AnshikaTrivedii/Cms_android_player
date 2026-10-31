package com.orion.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.orion.player.data.update.AppUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AppInstallResultReceiver : BroadcastReceiver() {
    @Inject lateinit var updates: AppUpdateManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        updates.onInstallResult(
            intent.getStringExtra("releaseId"),
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        )
    }

    companion object { const val ACTION_INSTALL_RESULT = "com.orion.player.APP_INSTALL_RESULT" }
}
