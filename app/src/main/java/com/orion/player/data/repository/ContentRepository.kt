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
import com.orion.player.data.sync.SyncDiagnostics
import com.orion.player.util.SessionGuard
import com.orion.player.util.UrlSecurityUtil
import android.util.Log
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

private const val TAG = "OrionSync"

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
        SyncDiagnostics.logDownloadStarted(asset)

        if (!asset.requiresDownload()) {
            SyncDiagnostics.logDownloadFailed(asset, "type ${asset.type} does not require download")
            return@withContext null
        }

        if (asset.normalizedType() == AssetType.URL) {
            val file = downloadUrlSnapshot(asset)
            if (file != null) {
                SyncDiagnostics.logDownloadSuccess(asset, file)
            } else {
                SyncDiagnostics.logDownloadFailed(
                    asset,
                    when {
                        asset.url.isNullOrBlank() -> "URL asset missing url field"
                        else -> "URL snapshot fetch failed"
                    }
                )
            }
            return@withContext file
        }

        val localFile = getLocalFile(asset)

        if (isCachedAssetValid(localFile, asset.fileSize)) {
            SyncDiagnostics.logDownloadSuccess(asset, localFile)
            return@withContext localFile
        }

        val legacyFile = findLegacyCachedFile(asset)
        if (legacyFile != null && isCachedAssetValid(legacyFile, asset.fileSize)) {
            SyncDiagnostics.logDownloadSuccess(asset, legacyFile)
            return@withContext legacyFile
        }

        val downloadUrl = asset.downloadUrl
        if (downloadUrl.isNullOrBlank()) {
            SyncDiagnostics.logDownloadFailed(asset, "missing downloadUrl")
            return@withContext null
        }

        val downloaded = downloadToCache(localFile, downloadUrl, asset.fileSize)
        if (downloaded != null) {
            SyncDiagnostics.logDownloadSuccess(asset, downloaded)
        } else {
            SyncDiagnostics.logDownloadFailed(asset, "HTTP download failed for ${asset.name}")
        }
        downloaded
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
        // Presigned URLs and HTML snapshots may not match CMS fileSize exactly.
        if (expectedSize > 0 && localFile.length() != expectedSize.toLong()) {
            Log.w(
                TAG,
                "Cached file size mismatch for ${localFile.name}: " +
                    "expected=$expectedSize actual=${localFile.length()} — using cache anyway"
            )
        }
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

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} downloading ${localFile.name} from $sourceUrl")
                return null
            }

            response.body?.byteStream()?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (expectedSize > 0 && tempFile.length() != expectedSize.toLong()) {
                Log.w(
                    TAG,
                    "Downloaded size mismatch for ${localFile.name}: " +
                        "expected=$expectedSize actual=${tempFile.length()} — accepting file"
                )
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
                val existing = findCachedFileForAsset(asset)
                if (existing != null) {
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
        val file = findCachedFileForAsset(asset) ?: return false
        return isCachedAssetValid(file, asset.fileSize)
    }

    /**
     * Resolves a cached file by canonical key or legacy key prefix (pre-v1.0.8 cache keys).
     */
    fun findCachedFileForAsset(asset: AssetInfo): File? {
        val canonical = getLocalFile(asset)
        if (canonical.exists() && canonical.length() > 0L) return canonical
        return findLegacyCachedFile(asset)
    }

    fun countCacheFilesOnDisk(): Int =
        cacheDir.listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } ?: 0

    private fun findLegacyCachedFile(asset: AssetInfo): File? {
        val extension = when (asset.normalizedType()) {
            AssetType.URL, AssetType.HTML -> "html"
            else -> asset.name.substringAfterLast('.', "bin")
        }
        val prefix = "${asset.id}_"
        return cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".$extension") }
            ?.firstOrNull { it.length() > 0L }
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
        // Stable per asset — do not include presigned downloadUrl (rotates every sync).
        val fingerprint = listOf(
            id,
            name,
            type,
            mimeType,
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
