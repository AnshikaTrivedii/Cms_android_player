package com.orion.player.data.cache

import android.util.Log
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.normalizedType
import java.io.File

/**
 * Structured download + cache diagnostics.
 * Filter logcat: adb logcat -s OrionCache
 */
object CacheDownloadLogger {
    private const val TAG = "OrionCache"

    fun logCacheDirectory(path: String, totalBytes: Long, fileCount: Int) {
        Log.i(
            TAG,
            "Cache directory: path=$path size=${formatBytes(totalBytes)} files=$fileCount"
        )
    }

    fun logDownloadStarted(asset: AssetInfo) {
        Log.i(
            TAG,
            "Download STARTED: name=${asset.name} type=${asset.normalizedType()} " +
                "id=${asset.id} expectedSize=${asset.fileSize}"
        )
    }

    fun logDownloadCompleted(asset: AssetInfo, file: File) {
        Log.i(
            TAG,
            "Download COMPLETED: name=${asset.name} type=${asset.normalizedType()} " +
                "id=${asset.id} fileSize=${file.length()} path=${file.absolutePath}"
        )
    }

    fun logDownloadFailed(asset: AssetInfo, reason: String) {
        Log.e(
            TAG,
            "Download FAILED: name=${asset.name} type=${asset.normalizedType()} " +
                "id=${asset.id} reason=$reason"
        )
    }

    fun logCacheHit(asset: AssetInfo, file: File) {
        Log.i(
            TAG,
            "Cache HIT (reused): name=${asset.name} type=${asset.normalizedType()} " +
                "id=${asset.id} fileSize=${file.length()} path=${file.absolutePath}"
        )
    }

    fun logCleanupDeleted(file: File) {
        Log.i(
            TAG,
            "Cleanup DELETED: name=${file.name} size=${file.length()} path=${file.absolutePath}"
        )
    }

    fun logCleanupSkipped(file: File, reason: String) {
        Log.d(TAG, "Cleanup SKIPPED: name=${file.name} reason=$reason")
    }

    fun logValidationMissing(asset: AssetInfo) {
        Log.w(
            TAG,
            "Validation MISSING: name=${asset.name} type=${asset.normalizedType()} id=${asset.id}"
        )
    }

    fun logValidationSummary(total: Int, missing: Int) {
        Log.i(TAG, "Validation summary: total=$total missing=$missing playable=${total - missing}")
    }

    fun logOfflinePlayback(playlistName: String, assetName: String) {
        Log.i(TAG, "Offline playback: playlist=$playlistName asset=$assetName (local cache only)")
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
