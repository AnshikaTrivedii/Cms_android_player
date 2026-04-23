package com.orion.player.data.repository

import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.InitPairingRequest
import com.orion.player.data.remote.InitPairingResponse
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.PairingStatusResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository handling the device pairing lifecycle:
 * init-pairing → poll status → store credentials.
 */
@Singleton
class PairingRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val securePrefs: SecurePrefs
) {
    /**
     * Initiates pairing by sending the hardware ID to the backend.
     * Returns the pairing response (code + isPaired status).
     * Idempotent — safe to call multiple times with the same hardwareId.
     */
    suspend fun initPairing(hardwareId: String): InitPairingResponse {
        return api.initPairing(InitPairingRequest(hardwareId))
    }

    /**
     * Polls the pairing status every [intervalMs] until the device is paired.
     * Emits each poll result as a Flow.
     * When isPaired becomes true, saves credentials to SecurePrefs and stops.
     */
    fun pollPairingStatus(
        hardwareId: String,
        intervalMs: Long = 5_000L
    ): Flow<PairingStatusResponse> = flow {
        while (true) {
            val status = api.getPairingStatus(hardwareId)
            emit(status)

            if (status.isPaired && status.deviceToken != null && status.organizationId != null) {
                // Persist credentials securely
                securePrefs.savePairingCredentials(
                    deviceToken = status.deviceToken,
                    organizationId = status.organizationId,
                    deviceName = status.deviceName
                )
                break
            }

            delay(intervalMs)
        }
    }

    /**
     * Checks if the device is already paired (from local storage).
     */
    fun isAlreadyPaired(): Boolean = securePrefs.isPaired

    /**
     * Returns the persisted or newly generated hardware ID.
     */
    fun getHardwareId(): String = securePrefs.getOrCreateHardwareId()

    /**
     * Clears stored credentials (e.g., on 401 or manual unpair).
     */
    fun clearPairing() {
        securePrefs.clearCredentials()
    }
}
