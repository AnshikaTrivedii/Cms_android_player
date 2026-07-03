package com.orion.player.data.enterprise

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.DevicePermissionsPayload
import com.orion.player.receiver.BootReceiver
import com.orion.player.service.PlayerForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevicePermissionReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePrefs
) {
    fun snapshot(): DevicePermissionSnapshot {
        val pm = context.packageManager
        val pkg = context.packageName
        val hasInternet = hasPermission(Manifest.permission.INTERNET)
        val hasNetwork = hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        val hasBoot = pm.queryBroadcastReceivers(
            android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED).setClass(
                context,
                BootReceiver::class.java
            ),
            PackageManager.GET_META_DATA
        ).isNotEmpty()
        val hasFgs = runCatching {
            pm.getServiceInfo(
                android.content.ComponentName(context, PlayerForegroundService::class.java),
                PackageManager.GET_META_DATA
            )
            true
        }.getOrDefault(false)
        val hasWakeLock = hasPermission(Manifest.permission.WAKE_LOCK)
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val batteryOptIgnored = isBatteryOptimizationIgnored()
        return DevicePermissionSnapshot(
            internet = hasInternet,
            networkState = hasNetwork,
            bootReceiver = hasBoot,
            foregroundService = hasFgs,
            wakeLock = hasWakeLock,
            postNotifications = hasNotifications,
            batteryOptimizationIgnored = batteryOptIgnored,
            autoStartLikely = hasBoot && batteryOptIgnored,
            kioskModeEnabled = securePrefs.kioskModeEnabled
        )
    }

    fun toHeartbeatPayload(): DevicePermissionsPayload {
        val snapshot = snapshot()
        return DevicePermissionsPayload(
            internet = snapshot.internet,
            storage = true,
            foregroundService = snapshot.foregroundService,
            bootReceiver = snapshot.bootReceiver,
            wakeLock = snapshot.wakeLock,
            notification = snapshot.postNotifications,
            batteryOptimizationDisabled = snapshot.batteryOptimizationIgnored,
            autoStart = snapshot.autoStartLikely,
            kioskMode = snapshot.kioskModeEnabled
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
