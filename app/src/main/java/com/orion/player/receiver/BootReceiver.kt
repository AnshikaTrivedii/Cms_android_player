package com.orion.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.orion.player.data.enterprise.EnterpriseEntryPoint
import com.orion.player.data.recovery.AutoStartLogger
import com.orion.player.data.recovery.BootStateStore
import com.orion.player.data.recovery.PlayerLaunchHelper
import dagger.hilt.android.EntryPointAccessors

/**
 * Automatically launches Orion Player after device boot, power restore, OEM quick-boot,
 * an app update, or a delayed crash-loop relaunch.
 *
 * The receiver is `directBootAware`, so it also runs before the user unlocks the device.
 * In that state only device-protected storage exists: the encrypted preferences, the Room
 * database and the content cache are all unreadable, and the foreground service is not
 * direct-boot aware. Locked boot therefore only records the event and returns; the real
 * startup happens on BOOT_COMPLETED, which the platform delivers after unlock.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val bootState = BootStateStore.from(appContext)

        when {
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                AutoStartLogger.bootReceived(action, deferred = true)
                bootState.recordLockedBoot(action)
            }

            action in BOOT_ACTIONS -> {
                AutoStartLogger.bootReceived(action, deferred = false)
                if (!isUserUnlocked(appContext)) {
                    // Defensive: an OEM sending BOOT_COMPLETED before unlock must not make
                    // us touch credential-protected storage.
                    bootState.recordLockedBoot(action)
                    return
                }
                if (!bootState.shouldHandleBoot()) {
                    AutoStartLogger.bootDuplicateIgnored(action)
                    return
                }
                bootState.recordHandledBoot(action)
                logBootToEnterpriseStore(appContext, action)
                PlayerLaunchHelper.launchFromBoot(appContext, action)
            }

            action == Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!isUserUnlocked(appContext)) return
                logBootToEnterpriseStore(appContext, "package.replaced")
                PlayerLaunchHelper.launchPlayer(appContext, "package.replaced")
            }
        }
    }

    private fun isUserUnlocked(context: Context): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return true
        return runCatching { userManager.isUserUnlocked }.getOrDefault(true)
    }

    private fun logBootToEnterpriseStore(context: Context, action: String) {
        // Hilt + encrypted storage: only reachable after unlock, and never fatal.
        runCatching {
            EntryPointAccessors.fromApplication(context, EnterpriseEntryPoint::class.java)
                .deviceLogCollector()
                .logBoot(action)
        }
    }

    companion object {
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT"
        )
    }
}
