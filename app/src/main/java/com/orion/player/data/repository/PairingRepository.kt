package com.orion.player.data.repository

import android.util.Log
import com.orion.player.BuildConfig
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.InitPairingRequest
import com.orion.player.data.remote.InitPairingResponse
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.PairingStatusResponse
import com.orion.player.util.NetworkDiagnostics
import com.orion.player.util.retryOnNetworkFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val securePrefs: SecurePrefs,
    private val okHttpClient: OkHttpClient
) {
    suspend fun warmUpApi(): NetworkDiagnostics.WarmUpResult {
        Log.d(TAG, "Warming up API at ${BuildConfig.BASE_URL}")
        return NetworkDiagnostics.warmUpServer(okHttpClient)
    }

    suspend fun initPairing(hardwareId: String): InitPairingResponse {
        require(hardwareId.isNotBlank()) { "hardwareId is blank — cannot init pairing" }

        warmUpApi()

        Log.d(TAG, "init-pairing hardwareId=$hardwareId")
        val response = retryOnNetworkFailure("POST /player/init-pairing") {
            api.initPairing(InitPairingRequest(hardwareId))
        }

        Log.d(
            TAG,
            "init-pairing OK: code=${response.pairingCode}, " +
                "secretPresent=${!response.pairingSecret.isNullOrBlank()}, isPaired=${response.isPaired}"
        )

        response.pairingSecret?.takeIf { it.isNotBlank() }?.let { securePrefs.pairingSecret = it }
        response.pairingCode?.takeIf { it.isNotBlank() }?.let { securePrefs.pairingCode = it }
        return response
    }

    fun pollPairingStatus(
        hardwareId: String,
        pairingSecret: String,
        intervalMs: Long = 5_000L
    ): Flow<PairingStatusResponse> = flow {
        var activeSecret = pairingSecret.takeIf { it.isNotBlank() }
            ?: securePrefs.pairingSecret?.takeIf { it.isNotBlank() }
            ?: error("Missing pairing secret. Tap Retry to register again.")

        require(hardwareId.isNotBlank()) { "hardwareId is blank — cannot poll pairing status" }

        var consecutiveFailures = 0

        while (true) {
            try {
                Log.d(TAG, "pairing-status poll hardwareId=$hardwareId")
                val status = retryOnNetworkFailure(
                    endpoint = "GET /player/pairing-status",
                    maxAttempts = 2,
                    initialDelayMs = 3_000L
                ) {
                    fetchPairingStatus(hardwareId, activeSecret)
                }
                consecutiveFailures = 0
                emit(status)

                if (status.isPaired && status.deviceToken != null && status.organizationId != null) {
                    securePrefs.savePairingCredentials(
                        deviceToken = status.deviceToken,
                        organizationId = status.organizationId,
                        deviceName = status.deviceName
                    )
                    securePrefs.pairingSecret = null
                    securePrefs.pairingCode = null
                    break
                }
            } catch (e: Exception) {
                consecutiveFailures++
                NetworkDiagnostics.logException("GET /player/pairing-status", e)
                if (consecutiveFailures >= MAX_POLL_FAILURES) throw e
                Log.w(TAG, "Poll failed ($consecutiveFailures/$MAX_POLL_FAILURES), retrying...")
            }

            delay(intervalMs)
        }
    }

    private suspend fun fetchPairingStatus(
        hardwareId: String,
        pairingSecret: String
    ): PairingStatusResponse {
        val url = buildPairingStatusUrl(hardwareId, pairingSecret)
        Log.d(TAG, "pairing-status GET ${url.substringBefore('?')}?pairingSecret=<redacted>")
        return api.getPairingStatusByUrl(url)
    }

    private fun buildPairingStatusUrl(hardwareId: String, pairingSecret: String): String {
        val base = BuildConfig.BASE_URL.trimEnd('/')
        val encodedSecret = URLEncoder.encode(pairingSecret, Charsets.UTF_8.name())
        return "$base/player/pairing-status/$hardwareId?pairingSecret=$encodedSecret"
    }

    fun isAlreadyPaired(): Boolean =
        securePrefs.isPaired && !securePrefs.deviceToken.isNullOrBlank()

    fun getHardwareId(): String = securePrefs.getOrCreateHardwareId()

    fun clearPairing() {
        securePrefs.clearCredentials()
    }

    companion object {
        private const val TAG = "OrionPairing"
        private const val MAX_POLL_FAILURES = 5
    }
}
