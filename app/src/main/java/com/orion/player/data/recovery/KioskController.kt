package com.orion.player.data.recovery

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.orion.player.receiver.OrionDeviceAdminReceiver

/**
 * Lock Task (kiosk) support for dedicated-device deployments.
 *
 * A plain APK install never grants Device Owner, and calling `startLockTask()` without it
 * either throws or drops the device into user-confirmed screen pinning. So lock task is
 * only entered when the platform reports the package as lock-task permitted, which is true
 * when the device has been provisioned as a dedicated device (device owner, or a package
 * allow-listed by one). Everywhere else the player runs as a normal app.
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

    /** Remove the persistent Home policy left by versions that supported auto-start. */
    fun clearLegacyHomeAssignment(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching {
            dpm.clearPackagePersistentPreferredActivities(
                OrionDeviceAdminReceiver.componentName(context),
                context.packageName
            )
            true
        }.getOrDefault(false)
    }

    private inline fun logOnce(state: String, log: () -> Unit) {
        if (lastLoggedState == state) return
        lastLoggedState = state
        log()
    }
}
