package com.orion.player.data.sync

import android.util.Log

/**
 * Playlist refresh diagnostics.
 * Filter logcat: adb logcat -s OrionPlaylist
 */
object PlaylistRefreshLogger {
    private const val TAG = "OrionPlaylist"

    fun logVersionCheck(localVersion: Int?, remoteVersion: Int?) {
        Log.i(
            TAG,
            "Version check: local=${localVersion ?: "none"} remote=${remoteVersion ?: "none"} " +
                "changed=${localVersion != null && remoteVersion != null && localVersion != remoteVersion}"
        )
    }

    fun logDiff(diff: PlaylistDiff) {
        Log.i(
            TAG,
            "Playlist diff: localVersion=${diff.localVersion} newVersion=${diff.remoteVersion} " +
                "added=${diff.assetsAdded.size} removed=${diff.assetsRemoved.size} " +
                "orderChanged=${diff.orderChanged} metadataChanged=${diff.metadataChanged} " +
                "requiresDownload=${diff.requiresDownload} requiresQueueRebuild=${diff.requiresQueueRebuild}"
        )
        if (diff.assetsAdded.isNotEmpty()) {
            Log.i(TAG, "Assets added: ${diff.assetsAdded.joinToString()}")
        }
        if (diff.assetsRemoved.isNotEmpty()) {
            Log.i(TAG, "Assets removed: ${diff.assetsRemoved.joinToString()}")
        }
        if (diff.playlistRenamed) {
            Log.i(TAG, "Playlist renamed")
        }
    }

    fun logManifestRefetch(reason: String) {
        Log.i(TAG, "Fetching full playlist manifest: reason=$reason")
    }

    fun logQueueRebuilt(
        playlistName: String,
        assetCount: Int,
        resetIndex: Boolean,
        newVersion: Int?
    ) {
        Log.i(
            TAG,
            "Playback queue rebuilt: playlist=$playlistName assets=$assetCount " +
                "resetIndex=$resetIndex version=${newVersion ?: "none"}"
        )
    }

    fun logCacheUpdated(playlistName: String, version: Int?) {
        Log.i(
            TAG,
            "Cached playlist replaced: playlist=$playlistName version=${version ?: "none"}"
        )
    }

    fun logUnchangedAccepted(localVersion: Int?) {
        Log.d(TAG, "Playlist unchanged — local version=${localVersion ?: "none"} still current")
    }

    fun logLoopRebuilt(
        playlistId: String?,
        previousVersion: Int?,
        newVersion: Int?,
        assetCount: Int,
        rebuiltLoop: Boolean
    ) {
        Log.i(
            TAG,
            "playlistId=${playlistId.orEmpty()} previousVersion=${previousVersion ?: "none"} " +
                "newVersion=${newVersion ?: "none"} assetCount=$assetCount rebuiltLoop=$rebuiltLoop"
        )
    }
}
