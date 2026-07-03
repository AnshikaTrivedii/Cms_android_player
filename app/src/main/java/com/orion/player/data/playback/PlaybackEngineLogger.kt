package com.orion.player.data.playback

import android.util.Log

/**
 * Playback engine stability diagnostics.
 * Filter: adb logcat -s OrionPlayback
 */
object PlaybackEngineLogger {
    private const val TAG = "OrionPlayback"

    fun logSyncStarted(reason: String, displayingContent: Boolean, queueIndex: Int, assetName: String) {
        Log.i(
            TAG,
            "Sync started: reason=$reason displayingContent=$displayingContent " +
                "queueIndex=$queueIndex currentAsset=$assetName"
        )
    }

    fun logSyncCompleted(
        outcome: String,
        displayingContent: Boolean,
        staged: Boolean
    ) {
        Log.i(
            TAG,
            "Sync completed: outcome=$outcome displayingContent=$displayingContent staged=$staged"
        )
    }

    fun logQueueStaged(
        oldSize: Int,
        newSize: Int,
        playlistVersion: Int?,
        reason: String,
        currentAsset: String
    ) {
        Log.i(
            TAG,
            "Queue staged (background): oldSize=$oldSize newSize=$newSize " +
                "version=${playlistVersion ?: "none"} reason=$reason currentAsset=$currentAsset"
        )
    }

    fun logQueueSwapped(
        oldSize: Int,
        newSize: Int,
        playlistVersion: Int?,
        newIndex: Int
    ) {
        Log.i(
            TAG,
            "Queue swapped: oldSize=$oldSize newSize=$newSize " +
                "version=${playlistVersion ?: "none"} newIndex=$newIndex"
        )
    }

    fun logVideoDurationStop(assetName: String, configuredMs: Long, actualMs: Long) {
        Log.i(
            TAG,
            "Video duration stop: asset=$assetName configuredMs=$configuredMs actualMs=$actualMs"
        )
    }

    fun logVideoCompleted(assetName: String) {
        Log.i(TAG, "Video completed naturally: asset=$assetName")
    }

    fun logPlaybackContinuedDuringSync(assetName: String, queueIndex: Int) {
        Log.d(
            TAG,
            "No-black-screen: continuing playback during sync asset=$assetName index=$queueIndex"
        )
    }

    fun logDurationReceivedFromSync(
        assetName: String,
        oldDurationSec: Int?,
        newDurationSec: Int?,
        playlistVersion: Int?
    ) {
        Log.i(
            TAG,
            "Duration from sync: asset=$assetName oldSec=${oldDurationSec ?: "none"} " +
                "newSec=${newDurationSec ?: "default"} version=${playlistVersion ?: "none"}"
        )
    }

    fun logDurationMergedInMemory(
        assetName: String,
        oldDurationSec: Int?,
        newDurationSec: Int?
    ) {
        Log.i(
            TAG,
            "Duration merged (in-memory): asset=$assetName oldSec=${oldDurationSec ?: "none"} " +
                "newSec=${newDurationSec ?: "default"}"
        )
    }

    fun logDurationUsedDuringPlayback(
        assetName: String,
        configuredMs: Long,
        actualMs: Long
    ) {
        Log.i(
            TAG,
            "Duration used during playback: asset=$assetName configuredMs=$configuredMs actualMs=$actualMs"
        )
    }

    fun logDurationChangesFromSync(
        previous: List<com.orion.player.data.remote.AssetInfo>,
        synced: List<com.orion.player.data.remote.AssetInfo>,
        playlistVersion: Int?
    ) {
        val previousById = previous.associateBy { it.id }
        synced.forEach { asset ->
            val prior = previousById[asset.id] ?: return@forEach
            if (prior.cmsDurationSeconds != asset.cmsDurationSeconds) {
                logDurationReceivedFromSync(
                    assetName = asset.name,
                    oldDurationSec = prior.cmsDurationSeconds,
                    newDurationSec = asset.cmsDurationSeconds,
                    playlistVersion = playlistVersion
                )
            }
        }
    }
}
