package com.orion.player.data.registration

import android.util.Log

object DeviceRegistrationLogger {
    private const val TAG = "OrionDeviceReg"

    fun logUnregisteredDetected() = Log.i(TAG, "Device Unregistered detected")
    fun logDeletedDetected() = Log.i(TAG, "Device Deleted detected")
    fun logStoppingPlayback() = Log.i(TAG, "Stopping Playback")
    fun logSessionCleared(keepMedia: Boolean) =
        Log.i(TAG, "Session Cleared keepMediaCache=$keepMedia")
    fun logLocalRegistrationRemoved() = Log.i(TAG, "Local Registration Removed")
    fun logWaitingForPairing() = Log.i(TAG, "Waiting For Pairing")
    fun logPairScreenDisplayed() = Log.i(TAG, "Pair Screen Displayed")
    fun logNewPairingStarted() = Log.i(TAG, "New Pairing Started")
}
