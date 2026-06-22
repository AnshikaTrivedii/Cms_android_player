package com.orion.player.data.repository

import android.content.Context
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.data.remote.AssetType.requiresDownload
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.SyncResponse
import com.orion.player.data.remote.SyncRevisionResponse
import com.orion.player.util.SessionGuard
import com.orion.player.util.UrlSecurityUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val SIGNAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; OrionSignage) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * Repository for content synchronization and asset caching.
 * Downloads assets to internal cache and provides local file paths for playback.
 */
@Singleton
class ContentRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val securePrefs: SecurePrefs,
    private val sessionGuard: SessionGuard,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private val cacheDir: File
        get() = File(context.cacheDir, "orion_assets").also { it.mkdirs() }

    /**
     * Fetches the current playlist manifest from the backend.
     * Returns null if the device token is missing (not paired).
     */
    suspend fun syncPlaylist(): SyncResponse? {
        if (!sessionGuard.isPairedWithToken()) return null
        val token = sessionGuard.requirePairedToken()
        return api.syncPlaylist(token)
    }

    /**
     * Lightweight content revision check (~few bytes).
     * Used for near-real-time polling without downloading the full manifest.
     */
    suspend fun getSyncRevision(): SyncRevisionResponse {
        val token = sessionGuard.requirePairedToken()
        return api.getSyncRevision(token)
    }

    /**
     * Downloads an asset to local cache if not already cached.
     * Uses a temporary file during download to prevent corrupted partial files.
     * Returns the local File path, or null on failure.
     */
    suspend fun downloadAsset(asset: AssetInfo): File? = withContext(Dispatchers.IO) {
        if (!asset.requiresDownload()) return@withContext null

        if (asset.normalizedType() == AssetType.URL) {
            return@withContext downloadUrlSnapshot(asset)
        }

        val localFile = getLocalFile(asset)

        if (isCachedAssetValid(localFile, asset.fileSize)) {
            return@withContext localFile
        }

        val downloadUrl = asset.downloadUrl ?: return@withContext null
        return@withContext downloadToCache(localFile, downloadUrl, asset.fileSize)
    }

    /**
     * Caches a snapshot of a URL asset during sync for offline fallback playback.
     */
    private fun downloadUrlSnapshot(asset: AssetInfo): File? {
        val remoteUrl = UrlSecurityUtil.normalizeUrl(asset.url) ?: return null
        val localFile = getLocalFile(asset)

        if (isCachedAssetValid(localFile, asset.fileSize)) {
            return localFile
        }

        return downloadToCache(localFile, remoteUrl, asset.fileSize)
    }

    private fun isCachedAssetValid(localFile: File, expectedSize: Int): Boolean {
        if (!localFile.exists() || localFile.length() == 0L) return false
        if (expectedSize > 0) return localFile.length() == expectedSize.toLong()
        return true
    }

    private fun downloadToCache(localFile: File, sourceUrl: String, expectedSize: Int): File? {
        val tempFile = File(cacheDir, "${localFile.name}.tmp")

        try {
            val request = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", SIGNAGE_USER_AGENT)
                .build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) return null

            response.body?.byteStream()?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (expectedSize > 0 && tempFile.length() != expectedSize.toLong()) {
                tempFile.delete()
                return null
            }

            return if (tempFile.renameTo(localFile)) {
                localFile
            } else {
                tempFile.copyTo(localFile, overwrite = true)
                tempFile.delete()
                localFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile.delete()
            return null
        }
    }

    /**
     * Downloads all assets from a sync response that aren't already cached.
     * Reports progress via the optional [onProgress] callback (completed count, total count).
     * Returns a map of asset ID → local File.
     */
    suspend fun downloadAllAssets(
        assets: List<AssetInfo>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): Map<String, File> {
        val result = mutableMapOf<String, File>()

        val downloadableAssets = assets.filter { it.requiresDownload() }

        for ((index, asset) in downloadableAssets.withIndex()) {
            val file = downloadAsset(asset)
            if (file != null) {
                result[asset.id] = file
            } else {
                val existing = getLocalFile(asset)
                if (isAssetCached(asset)) {
                    result[asset.id] = existing
                }
            }
            onProgress?.invoke(index + 1, downloadableAssets.size.coerceAtLeast(1))
        }

        if (downloadableAssets.isEmpty() && assets.isNotEmpty()) {
            onProgress?.invoke(1, 1)
        }

        return result
    }

    /**
     * Returns the local file path for a given asset.
     */
    fun getLocalFile(asset: AssetInfo): File {
        val extension = when (asset.normalizedType()) {
            AssetType.URL, AssetType.HTML -> "html"
            else -> asset.name.substringAfterLast('.', "bin")
        }
        return File(cacheDir, "${asset.cacheKey()}.$extension")
    }

    /**
     * Checks if an asset is already cached locally.
     */
    fun isAssetCached(asset: AssetInfo): Boolean {
        val file = getLocalFile(asset)
        return isCachedAssetValid(file, asset.fileSize)
    }

    /**
     * Cleans up cached files that are no longer in the current playlist.
     */
    suspend fun cleanupStaleCache(currentCacheKeys: Set<String>) = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { file ->
            // Skip temp files — they may be actively downloading
            if (file.name.endsWith(".tmp")) return@forEach

            val cacheKey = file.nameWithoutExtension
            if (cacheKey !in currentCacheKeys) {
                file.delete()
            }
        }
    }

    fun getCacheKey(asset: AssetInfo): String = asset.cacheKey()

    private fun AssetInfo.cacheKey(): String {
        val fingerprint = listOf(
            id,
            name,
            type,
            mimeType,
            durationSeconds.toString(),
            position.toString(),
            downloadUrl.orEmpty(),
            fileSize.toString(),
            url.orEmpty()
        ).joinToString("|")

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)

        return "${id}_$hash"
    }
}
