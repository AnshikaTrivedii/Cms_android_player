package com.orion.player.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.util.Log

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
 * Once provisioned it unlocks Lock Task (kiosk) mode and persistent Home-app behaviour.
 * See docs/AUTO_START_PROVISIONING.md.
 */
class OrionDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled — dedicated device features available")
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled — kiosk and Home-app enforcement are now unavailable")
    }

    companion object {
        private const val TAG = "OrionAutoStart"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, OrionDeviceAdminReceiver::class.java)
    }
}
