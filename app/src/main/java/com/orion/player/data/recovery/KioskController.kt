package com.orion.player.data.recovery

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import com.orion.player.R
import com.orion.player.receiver.OrionDeviceAdminReceiver

/**
 * Lock Task (kiosk) support for dedicated-device deployments.
 *
 * A plain APK install never grants Device Owner, and calling `startLockTask()` without it
 * either throws or drops the device into user-confirmed screen pinning. So lock task is
 * only entered when the platform reports the package as lock-task permitted, which is true
 * when the device has been provisioned as a dedicated device (device owner, or a package
 * allow-listed by one). Everywhere else the player runs as a normal app and relies on the
 * HOME category plus onUserLeaveHint to stay in front.
 *
 * Provisioning steps live in docs/AUTO_START_PROVISIONING.md.
 */
object KioskController {

    private var lastLoggedState: String? = null

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    fun isLockTaskPermitted(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isLockTaskPermitted(context.packageName) }.getOrDefault(false)
    }

    /**
     * Enter lock task mode when the device allows it. Safe to call on every resume:
     * re-entering while already locked is a no-op and the log is emitted once per state.
     */
    fun applyIfPermitted(activity: Activity, kioskRequested: Boolean) {
        if (!kioskRequested) {
            logOnce("disabled") { AutoStartLogger.kioskModeNotAvailable("kiosk disabled in device settings") }
            return
        }

        if (!isLockTaskPermitted(activity)) {
            logOnce("not_permitted") {
                AutoStartLogger.kioskModeNotAvailable(
                    "device is not provisioned as a dedicated device (no device owner / lock task allow-list)"
                )
            }
            return
        }

        if (isInLockTaskMode(activity)) {
            logOnce("entered") { AutoStartLogger.kioskModeEntered(isDeviceOwner(activity)) }
            return
        }

        try {
            activity.startLockTask()
            lastLoggedState = "entered"
            AutoStartLogger.kioskModeEntered(isDeviceOwner(activity))
        } catch (error: IllegalStateException) {
            logOnce("illegal_state") {
                AutoStartLogger.kioskModeNotAvailable("startLockTask rejected: ${error.message}")
            }
        } catch (error: SecurityException) {
            logOnce("security") {
                AutoStartLogger.kioskModeNotAvailable("startLockTask denied: ${error.message}")
            }
        }
    }

    /**
     * Leave lock-task so another activity (Settings, an app) can open. Only called when
     * the operator explicitly chose to leave from the Home-escape overlay. [MainActivity]
     * re-enters lock-task on the next resume.
     */
    fun stopLockTaskIfActive(activity: Activity) {
        if (!isInLockTaskMode(activity)) return
        try {
            activity.stopLockTask()
            lastLoggedState = "stopped"
        } catch (_: Exception) {
            // Still attempt to launch the destination; lock-task may block it.
        }
    }

    fun isInLockTaskMode(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            manager.isInLockTaskMode
        }
    }

    /**
     * On a device-owner deployment, allow-list Orion for lock task so kiosk mode works
     * without any user confirmation. No-op on every non-provisioned device.
     */
    fun ensureLockTaskAllowed(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching {
            dpm.setLockTaskPackages(
                OrionDeviceAdminReceiver.componentName(context),
                arrayOf(context.packageName)
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Device-owner policy bundle: lock-task allow-list plus persistent Home.
     * No-op on a normal APK install.
     */
    fun applyDedicatedDevicePolicies(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val lockTask = ensureLockTaskAllowed(context)
        val home = applyHomeAppRole(context, enabled = true)
        return lockTask && home
    }

    /**
     * Ask the user (or the platform) to make Orion the default Home / launcher app.
     * Device-owner devices skip the picker and pin Home themselves.
     */
    fun requestHomeRole(activity: Activity): Boolean {
        if (AutoStartCoordinator.isDefaultHomeApp(activity)) {
            AutoStartLogger.homeAppStatus(isDefaultHome = true)
            return true
        }
        if (isDeviceOwner(activity)) {
            return applyHomeAppRole(activity, enabled = true)
        }
        val intent = homeRoleRequestIntent(activity) ?: return false
        return try {
            activity.startActivity(intent)
            AutoStartLogger.homeRoleRequested()
            true
        } catch (error: Exception) {
            AutoStartLogger.homeRoleRequestFailed(error)
            false
        }
    }

    fun homeRoleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
        if (homeSettings.resolveActivity(context.packageManager) != null) {
            return homeSettings
        }
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return Intent.createChooser(home, context.getString(R.string.home_role_chooser_title))
    }

    /**
     * On a device-owner deployment, make Orion the persistent Home app so Android itself
     * starts it at boot and returns to it on every Home press. This is the only reliable
     * auto-launch path on devices that block background activity starts.
     *
     * Called automatically when the app is device owner.
     */
    fun applyHomeAppRole(context: Context, enabled: Boolean): Boolean {
        if (!isDeviceOwner(context)) {
            AutoStartLogger.homeAppStatus(isDefaultHome = AutoStartCoordinator.isDefaultHomeApp(context))
            return false
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val admin = OrionDeviceAdminReceiver.componentName(context)
        return runCatching {
            if (enabled) {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(
                    admin,
                    filter,
                    ComponentName(context.packageName, MAIN_ACTIVITY)
                )
            } else {
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            }
            AutoStartLogger.homeAppStatus(isDefaultHome = enabled)
            true
        }.getOrDefault(false)
    }

    private inline fun logOnce(state: String, log: () -> Unit) {
        if (lastLoggedState == state) return
        lastLoggedState = state
        log()
    }

    private const val MAIN_ACTIVITY = "com.orion.player.MainActivity"
}
