package com.orion.player.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.orion.player.data.recovery.KioskController

/**
 * Device admin component required to provision Orion as a **device owner** on dedicated
 * signage hardware.
 *
 * Declaring this receiver grants nothing on its own — a normal APK install leaves it
 * inert. It only becomes active when the device is explicitly provisioned, e.g. on a
 * factory-reset device with no accounts:
 *
 *     adb shell dpm set-device-owner com.orion.player/.receiver.OrionDeviceAdminReceiver
 *
 * Once provisioned it unlocks Lock Task (kiosk) mode.
 */
class OrionDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled — dedicated device features available")
        KioskController.ensureLockTaskAllowed(context)
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled — kiosk enforcement is now unavailable")
    }

    companion object {
        private const val TAG = "OrionKiosk"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, OrionDeviceAdminReceiver::class.java)
    }
}
