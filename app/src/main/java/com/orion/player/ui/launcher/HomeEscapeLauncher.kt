package com.orion.player.ui.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import com.orion.player.data.recovery.AutoStartCoordinator
import com.orion.player.data.recovery.AutoStartLogger
import com.orion.player.data.recovery.KioskController

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable
)

/**
 * Opens Settings or another app from the Home-escape overlay, and pauses kiosk /
 * watchdog pull-back so the destination can stay in the foreground.
 */
object HomeEscapeLauncher {

    fun launchSettings(activity: Activity): Boolean {
        val settings = Intent(Settings.ACTION_SETTINGS)
        if (settings.resolveActivity(activity.packageManager) != null) {
            return launch(activity, "settings", settings)
        }
        val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
        if (homeSettings.resolveActivity(activity.packageManager) != null) {
            return launch(activity, "home_settings", homeSettings)
        }
        AutoStartLogger.userExitLaunchFailed(
            "settings",
            IllegalStateException("no settings activity resolved")
        )
        return false
    }

    fun hasSystemAllApps(context: Context): Boolean =
        allAppsIntent(context) != null

    fun launchAllAppsIfAvailable(activity: Activity): Boolean {
        val intent = allAppsIntent(activity) ?: return false
        return launch(activity, "all_apps", intent)
    }

    fun queryLaunchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val seen = linkedSetOf<String>()
        val apps = mutableListOf<LaunchableApp>()
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        )
        for (query in intents) {
            val resolved = queryActivities(pm, query)
            for (info in resolved) {
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) continue
                val activityName = info.activityInfo.name
                val key = "$packageName/$activityName"
                if (!seen.add(key)) continue
                apps += LaunchableApp(
                    label = info.loadLabel(pm).toString(),
                    packageName = packageName,
                    activityName = activityName,
                    icon = info.loadIcon(pm)
                )
            }
        }
        return apps.sortedBy { it.label.lowercase() }
    }

    fun launchApp(activity: Activity, app: LaunchableApp): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return launch(activity, "app:${app.packageName}", intent)
    }

    private fun launch(activity: Activity, target: String, intent: Intent): Boolean {
        AutoStartCoordinator.allowUserExit(target)
        KioskController.stopLockTaskIfActive(activity)
        val outbound = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.startActivity(outbound)
            true
        } catch (error: Exception) {
            AutoStartCoordinator.clearUserExit()
            AutoStartLogger.userExitLaunchFailed(target, error)
            false
        }
    }

    private fun allAppsIntent(context: Context): Intent? {
        val intent = Intent(Intent.ACTION_ALL_APPS)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    @Suppress("DEPRECATION")
    private fun queryActivities(
        pm: PackageManager,
        intent: Intent
    ) = pm.queryIntentActivities(intent, 0)
}
