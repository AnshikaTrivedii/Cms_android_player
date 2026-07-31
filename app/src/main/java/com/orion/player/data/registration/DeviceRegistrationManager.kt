package com.orion.player.data.registration

import android.util.Log
import com.orion.player.data.local.HeartbeatQueueDao
import com.orion.player.data.local.PlaylistCacheDao
import com.orion.player.data.local.PopLogDao
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.repository.ContentRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central handler for CMS unregister / delete.
 * Network failures must never call [handleStatus].
 *
 * Callers should stop playback + background schedulers after [requiresPairing] becomes true.
 */
@Singleton
class DeviceRegistrationManager @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val contentRepository: ContentRepository,
    private val playlistCacheDao: PlaylistCacheDao,
    private val popLogDao: PopLogDao,
    private val heartbeatQueueDao: HeartbeatQueueDao
) {
    private val mutex = Mutex()

    private val _registrationEvents =
        MutableSharedFlow<DeviceRegistrationStatus>(extraBufferCapacity = 4)
    val registrationEvents: SharedFlow<DeviceRegistrationStatus> = _registrationEvents.asSharedFlow()

    private val _requiresPairing = MutableStateFlow(false)
    val requiresPairing: StateFlow<Boolean> = _requiresPairing.asStateFlow()

    /**
     * Observe REGISTERED / UNREGISTERED / DELETED from any authenticated CMS call.
     * Returns true when a session-clearing action was applied.
     */
    suspend fun handleStatus(status: DeviceRegistrationStatus?): Boolean {
        if (status == null || status == DeviceRegistrationStatus.REGISTERED) return false
        return mutex.withLock {
            if (!securePrefs.isAuthenticated() && _requiresPairing.value) {
                Log.i(TAG, "Ignoring duplicate ${status.name} — already awaiting pairing")
                return@withLock true
            }
            when (status) {
                DeviceRegistrationStatus.UNREGISTERED -> applyUnregistered()
                DeviceRegistrationStatus.DELETED -> applyDeleted()
                DeviceRegistrationStatus.REGISTERED -> false
            }
        }
    }

    fun markPairingScreenDisplayed() {
        DeviceRegistrationLogger.logPairScreenDisplayed()
    }

    fun markNewPairingStarted() {
        _requiresPairing.value = false
        DeviceRegistrationLogger.logNewPairingStarted()
    }

    private suspend fun applyUnregistered(): Boolean {
        DeviceRegistrationLogger.logUnregisteredDetected()
        DeviceRegistrationLogger.logStoppingPlayback()
        clearSessionMetadata(keepMediaCache = true)
        DeviceRegistrationLogger.logSessionCleared(keepMedia = true)
        DeviceRegistrationLogger.logWaitingForPairing()
        _requiresPairing.value = true
        _registrationEvents.emit(DeviceRegistrationStatus.UNREGISTERED)
        return true
    }

    private suspend fun applyDeleted(): Boolean {
        DeviceRegistrationLogger.logDeletedDetected()
        DeviceRegistrationLogger.logStoppingPlayback()
        clearSessionMetadata(keepMediaCache = false)
        DeviceRegistrationLogger.logLocalRegistrationRemoved()
        DeviceRegistrationLogger.logSessionCleared(keepMedia = false)
        DeviceRegistrationLogger.logWaitingForPairing()
        _requiresPairing.value = true
        _registrationEvents.emit(DeviceRegistrationStatus.DELETED)
        return true
    }

    private suspend fun clearSessionMetadata(keepMediaCache: Boolean) {
        // Always clear playlist metadata / sync state so offline boot cannot resume old content.
        playlistCacheDao.clearAssets()
        playlistCacheDao.clearTickers()
        playlistCacheDao.clearPlaylist()
        heartbeatQueueDao.deleteAll()
        popLogDao.deleteAll()

        if (!keepMediaCache) {
            contentRepository.clearAllCacheFiles()
        }

        securePrefs.clearCredentials()
    }

    companion object {
        private const val TAG = "OrionDeviceReg"
    }
}
