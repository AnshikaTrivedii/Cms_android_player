package com.orion.player

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.webkit.WebView
import com.orion.player.data.enterprise.DeviceLogCollector
import com.orion.player.data.enterprise.CrashLogStore
import com.orion.player.data.recovery.AutoStartLogger
import com.orion.player.data.recovery.BootStateStore
import com.orion.player.data.recovery.CrashRecovery
import com.orion.player.data.recovery.OrionRecoveryLogger
import com.orion.player.data.recovery.PlayerLaunchHelper
import com.orion.player.util.NetworkDiagnostics
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OrionPlayerApp : Application() {

    @Inject lateinit var crashLogStore: CrashLogStore
    @Inject lateinit var deviceLogCollector: DeviceLogCollector

    override fun onCreate() {
        super.onCreate()
        NetworkDiagnostics.logStartupConfig()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("orion_player")
        }
        CrashRecovery.install(this, crashLogStore, deviceLogCollector)

        // The direct-boot-aware boot receiver can start this process before the user
        // unlocks the device. Nothing below is usable then: the encrypted preferences, the
        // database, the asset cache and the foreground service all live behind credential-
        // protected storage. BOOT_COMPLETED arrives after unlock and starts everything.
        if (!isUserUnlocked()) {
            Log.i("OrionPlayer", "Application started before user unlock — deferring startup")
            return
        }

        reportProcessRecovery()
        PlayerLaunchHelper.startProtection(this)
        deviceLogCollector.logBoot("application.onCreate")
        Log.i("OrionPlayer", "Application started")
        OrionRecoveryLogger.logPlayerStarted("application.onCreate")
    }

    private fun isUserUnlocked(): Boolean {
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
        return runCatching { userManager.isUserUnlocked }.getOrDefault(true)
    }

    /**
     * The previous process marked the player as running and never recorded a clean stop,
     * so it was killed by the system or crashed. Playback state itself is rebuilt from the
     * cache by the playback view model; this only makes the recovery visible in the logs.
     */
    private fun reportProcessRecovery() {
        val store = BootStateStore.from(this)
        val uncleanForMs = store.consumeUncleanShutdown() ?: return
        // A device reboot also leaves the flag set; that is a boot, not a process kill,
        // and the boot receiver already logged it.
        if (store.bootLaunchPending) return
        AutoStartLogger.processRecovery(uncleanForMs)
        deviceLogCollector.logBoot("process.recovery")
    }
}
