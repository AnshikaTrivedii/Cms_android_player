package com.orion.player.data.recovery

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
     * On a device-owner deployment, make Orion the persistent Home app so Android itself
     * starts it at boot and returns to it on every Home press. This is the only reliable
     * auto-launch path on devices that block background activity starts.
     *
     * Never called automatically: it is gated behind an explicit device setting so a normal
     * installation never takes over the user's launcher.
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
