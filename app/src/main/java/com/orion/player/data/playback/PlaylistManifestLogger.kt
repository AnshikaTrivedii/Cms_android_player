package com.orion.player.data.playback

import android.util.Log
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.data.remote.PlaylistInfo

/**
 * Logs the playlist manifest received from CMS / cache.
 * Filter: adb logcat -s OrionPlayback
 */
object PlaylistManifestLogger {
    private const val TAG = "OrionPlayback"

    fun logManifest(
        source: String,
        playlistId: String?,
        playlistName: String?,
        playlistVersion: Int?,
        assets: List<AssetInfo>
    ) {
        val ordered = assets.inPlaylistOrder()
        Log.i(
            TAG,
            "CMS manifest [$source]: playlistId=${playlistId.orEmpty()} " +
                "name=${playlistName.orEmpty()} version=${playlistVersion ?: "none"} " +
                "totalAssets=${ordered.size}"
        )
        ordered.forEachIndexed { index, asset ->
            Log.i(
                TAG,
                "CMS asset #${index + 1}: queuePos=${asset.position} id=${asset.id} " +
                    "name=${asset.name} type=${asset.normalizedType()} " +
                    "durationSec=${asset.cmsDurationSeconds ?: "default"}"
            )
        }
        Log.i(TAG, "CMS order: ${ordered.map { it.position to it.name }}")
    }

    fun logQueue(
        playlist: PlaylistInfo?,
        playlistVersion: Int?,
        assets: List<AssetInfo>,
        currentIndex: Int
    ) {
        val ordered = assets.inPlaylistOrder()
        Log.i(
            TAG,
            "Player queue: playlist=${playlist?.name.orEmpty()} version=${playlistVersion ?: "none"} " +
                "size=${ordered.size} currentIndex=$currentIndex " +
                "current=${ordered.getOrNull(currentIndex)?.name.orEmpty()}"
        )
        Log.i(
            TAG,
            "Player queue order: ${ordered.mapIndexed { i, a -> "${i + 1}:${a.name}@${a.position}" }}"
        )
    }
}
