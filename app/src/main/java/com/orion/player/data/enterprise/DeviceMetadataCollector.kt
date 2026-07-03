package com.orion.player.data.enterprise

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.orion.player.BuildConfig
import com.orion.player.data.local.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects static and runtime device identity fields expected by the CMS heartbeat API.
 */
@Singleton
class DeviceMetadataCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePrefs
) {
    fun androidVersion(): String =
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun playerVersion(): String = BuildConfig.VERSION_NAME

    fun manufacturer(): String = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" }

    fun deviceModel(): String = Build.MODEL.orEmpty().ifBlank { "Unknown" }

    fun deviceName(): String? = securePrefs.deviceName?.takeIf { it.isNotBlank() }

    fun ipAddress(): String? = readLocalIpAddress()

    fun macAddress(): String = readMacAddress()

    fun resolution(): String {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return "${metrics.widthPixels}x${metrics.heightPixels}"
    }

    fun orientation(): String =
        when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
            else -> "UNKNOWN"
        }

    fun timezone(): String = TimeZone.getDefault().id

    fun lastSyncTime(): String? = securePrefs.lastSuccessfulSyncAt

    fun registrationSnapshot(): DeviceRegistrationSnapshot = DeviceRegistrationSnapshot(
        androidVersion = androidVersion(),
        playerVersion = playerVersion(),
        manufacturer = manufacturer(),
        deviceModel = deviceModel(),
        deviceName = deviceName(),
        ip = ipAddress(),
        macAddress = macAddress().takeIf { it.isNotBlank() },
        resolution = resolution(),
        orientation = orientation(),
        timezone = timezone()
    )

    fun heartbeatSnapshot(
        currentAsset: String?,
        currentPlaylistName: String?,
        playbackStatus: String,
        playbackUptimeSeconds: Long,
        networkOnline: Boolean
    ): DeviceHeartbeatMetadata = DeviceHeartbeatMetadata(
        androidVersion = androidVersion(),
        playerVersion = playerVersion(),
        manufacturer = manufacturer(),
        deviceModel = deviceModel(),
        deviceName = deviceName(),
        ip = ipAddress(),
        macAddress = macAddress().takeIf { it.isNotBlank() },
        resolution = resolution(),
        orientation = orientation(),
        timezone = timezone(),
        lastSyncTime = lastSyncTime(),
        currentAsset = currentAsset,
        currentPlaylistName = currentPlaylistName,
        playbackStatus = playbackStatus,
        playbackUptimeSeconds = playbackUptimeSeconds,
        networkStatus = if (networkOnline) "ONLINE" else "OFFLINE"
    )

    fun recordSuccessfulSync(at: Instant = Instant.now()) {
        securePrefs.lastSuccessfulSyncAt = at.toString()
    }

    private fun readLocalIpAddress(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val linkProperties = cm.getLinkProperties(network) ?: return null
            linkProperties.linkAddresses
                .firstOrNull { address ->
                    address.address is Inet4Address && !address.address.isLoopbackAddress
                }
                ?.address
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun readMacAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val name = networkInterface.name.lowercase(Locale.US)
                if (name != "wlan0" && name != "eth0" && !name.startsWith("wlan")) continue
                val hardwareAddress = networkInterface.hardwareAddress ?: continue
                if (hardwareAddress.isEmpty()) continue
                val formatted = hardwareAddress.joinToString(":") { byte ->
                    String.format(Locale.US, "%02X", byte)
                }
                if (formatted.isNotBlank() && formatted != "02:00:00:00:00:00") {
                    return formatted
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}

data class DeviceRegistrationSnapshot(
    val androidVersion: String,
    val playerVersion: String,
    val manufacturer: String,
    val deviceModel: String,
    val deviceName: String?,
    val ip: String?,
    val macAddress: String?,
    val resolution: String,
    val orientation: String,
    val timezone: String
)

data class DeviceHeartbeatMetadata(
    val androidVersion: String,
    val playerVersion: String,
    val manufacturer: String,
    val deviceModel: String,
    val deviceName: String?,
    val ip: String?,
    val macAddress: String?,
    val resolution: String,
    val orientation: String,
    val timezone: String,
    val lastSyncTime: String?,
    val currentAsset: String?,
    val currentPlaylistName: String?,
    val playbackStatus: String,
    val playbackUptimeSeconds: Long,
    val networkStatus: String
)
