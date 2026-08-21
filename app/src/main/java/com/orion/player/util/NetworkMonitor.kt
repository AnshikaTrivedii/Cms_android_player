package com.orion.player.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide internet connectivity.
 *
 * Online only when the active network has both INTERNET and VALIDATED.
 * Wi-Fi/Ethernet linked with no path to the internet is offline.
 *
 * Callbacks are registered on the main looper and live for the process so
 * playback does not unregister them. A failed API/sync request does not
 * change this signal.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _online = MutableStateFlow(hasValidatedInternet())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    val isOnline: Boolean
        get() = hasValidatedInternet()

    private fun newCallback(source: String) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            debug(
                "networkCallback available source=$source network=$network " +
                    "caps=${capsSummary(network)}"
            )
            publishFromCallback("available:$source")
        }

        override fun onLost(network: Network) {
            debug("networkCallback lost source=$source network=$network")
            publishFromCallback("lost:$source")
        }

        override fun onUnavailable() {
            debug("networkCallback unavailable source=$source")
            publish(false, reason = "unavailable:$source")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            debug(
                "network capabilities changed source=$source network=$network " +
                    "caps=${capsSummary(networkCapabilities)} " +
                    "validated internet=${isValidatedInternet(networkCapabilities)}"
            )
            publishFromCallback("capabilities:$source")
        }
    }

    init {
        debug(
            "NetworkMonitor init thread=${Thread.currentThread().name} " +
                "hasLooper=${Looper.myLooper() != null} " +
                "validated internet=${hasValidatedInternet()} " +
                "current connectivity state=${statusLabel(hasValidatedInternet())}"
        )
        mainHandler.post {
            registerCallbacks()
        }
    }

    fun observeOnline(): Flow<Boolean> = online

    private fun registerCallbacks() {
        val defaultCallback = newCallback("default")
        val requestCallback = newCallback("request")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connectivityManager.registerDefaultNetworkCallback(defaultCallback, mainHandler)
            } else {
                connectivityManager.registerDefaultNetworkCallback(defaultCallback)
            }
            debug("NetworkCallback REGISTERED source=default")
        } catch (error: Exception) {
            Log.e(TAG, "OFFLINE_DEBUG: NetworkCallback default register failed: ${error.message}", error)
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connectivityManager.registerNetworkCallback(request, requestCallback, mainHandler)
            } else {
                connectivityManager.registerNetworkCallback(request, requestCallback)
            }
            debug("NetworkCallback REGISTERED source=internet-request")
        } catch (error: Exception) {
            Log.e(TAG, "OFFLINE_DEBUG: NetworkCallback request register failed: ${error.message}", error)
        }

        publishFromCallback("registered")
    }

    private fun publishFromCallback(reason: String) {
        val validated = hasValidatedInternet()
        debug(
            "recompute reason=$reason validated internet=$validated " +
                "activeNetwork=${connectivityManager.activeNetwork} " +
                "caps=${capsSummary(connectivityManager.activeNetwork)} " +
                "current connectivity state=${statusLabel(validated)}"
        )
        publish(validated, reason)
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isValidatedInternet(caps)
    }

    private fun isValidatedInternet(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun publish(online: Boolean, reason: String) {
        val previous = _online.value
        if (previous == online) {
            debug("state unchanged ${statusLabel(online)} reason=$reason")
            return
        }
        _online.value = online
        val status = statusLabel(online)
        Log.i(TAG, "NETWORK_STATUS_CHANGED status=$status timestamp=${Instant.now()} reason=$reason")
        debug("networkState=$status")
    }

    private fun capsSummary(network: Network?): String {
        if (network == null) return "none"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "null"
        return capsSummary(caps)
    }

    private fun capsSummary(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
        }.joinToString("|").ifBlank { "unknown" }
        return "transport=$transports internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    private fun statusLabel(online: Boolean): String = if (online) "ONLINE" else "OFFLINE"

    private fun debug(message: String) {
        Log.i(TAG, "OFFLINE_DEBUG: $message timestamp=${Instant.now()}")
    }

    companion object {
        private const val TAG = "OrionNetwork"
    }
}
