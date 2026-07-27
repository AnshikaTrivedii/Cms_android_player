package com.orion.player.data.sync

import android.util.Log
import com.orion.player.data.analytics.PopLogFlushScheduler
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.telemetry.DeviceHeartbeatScheduler
import com.orion.player.service.PlayerForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs immediately after pairing succeeds so the first heartbeat and initial sync
 * begin without waiting for playback or the 60s heartbeat timer.
 */
@Singleton
class PostPairingBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePrefs,
    private val heartbeatScheduler: DeviceHeartbeatScheduler,
    private val contentSyncScheduler: ContentSyncScheduler,
    private val popLogFlushScheduler: PopLogFlushScheduler,
    private val initialSyncCoordinator: InitialSyncCoordinator
) {
    suspend fun onPairingCompleted() {
        if (!securePrefs.isAuthenticated()) return

        Log.i(TAG, "Bootstrapping paired device tokenPrefix=${securePrefs.deviceTokenPrefix()}")
        PlayerForegroundService.start(context)

        initialSyncCoordinator.onPairingCompleted()

        heartbeatScheduler.start()
        contentSyncScheduler.start()
        popLogFlushScheduler.start()

        val response = heartbeatScheduler.sendHeartbeatNow()
        if (response != null) {
            initialSyncCoordinator.handleHeartbeatResponse(response)
        } else {
            Log.w(TAG, "Immediate post-pairing heartbeat failed — retry loop will continue")
        }
    }

    companion object {
        private const val TAG = "OrionPairing"
    }
}
