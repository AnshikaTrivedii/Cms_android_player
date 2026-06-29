package com.orion.player.data.repository

import android.util.Log
import com.orion.player.data.cache.CacheDownloadLogger
import com.orion.player.data.cache.ContentCacheManager
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.data.remote.AssetType.remoteSourceUrl
import com.orion.player.data.remote.AssetType.requiresDownload as assetTypeRequiresDownload
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.SyncResponse
import com.orion.player.data.remote.SyncRevisionResponse
import com.orion.player.data.sync.SyncDiagnostics
import com.orion.player.util.NetworkDiagnostics
import com.orion.player.util.retryOnNetworkFailure
import com.orion.player.util.SessionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val SIGNAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; OrionSignage) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val TAG = "OrionSync"

/**
 * Repository for content synchronization and asset caching.
 * Downloads only assets from the current playlist manifest into [ContentCacheManager]'s directory.
 */
@Singleton
class ContentRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val securePrefs: SecurePrefs,
    private val sessionGuard: SessionGuard,
    private val okHttpClient: OkHttpClient,
    private val contentCacheManager: ContentCacheManager
) {
    private val cacheDir: File
        get() = contentCacheManager.getContentDirectory()

    fun getContentCacheDirectory(): File = cacheDir

    fun getContentCacheStats() = contentCacheManager.getCacheStats()

    suspend fun syncPlaylist(
        playlistVersion: Int? = null,
        layoutVersion: Int? = null,
        knownAssetIds: List<String>? = null,
        assetVersions: Map<String, Int>? = null
    ): SyncResponse? {
        if (!sessionGuard.isPairedWithToken()) return null
        NetworkDiagnostics.warmUpServer(okHttpClient)
        val token = sessionGuard.requirePairedToken()
        return retryOnNetworkFailure(endpoint = "GET /player/sync", maxAttempts = 4) {
            api.syncPlaylist(
                token = token,
                playlistVersion = playlistVersion,
                layoutVersion = layoutVersion,
                knownAssetIds = knownAssetIds?.takeIf { it.isNotEmpty() }?.joinToString(","),
                assetVersions = assetVersions?.takeIf { it.isNotEmpty() }
                    ?.entries
                    ?.joinToString(",") { "${it.key}:${it.value}" }
            )
        }
    }

    suspend fun getSyncRevision(): SyncRevisionResponse {
        val token = sessionGuard.requirePairedToken()
        return api.getSyncRevision(token)
    }

    suspend fun downloadAsset(asset: AssetInfo): File? = withContext(Dispatchers.IO) {
        CacheDownloadLogger.logDownloadStarted(asset)
        SyncDiagnostics.logDownloadStarted(asset)

        if (!asset.assetTypeRequiresDownload()) {
            val reason = "type ${asset.type} does not require download"
            CacheDownloadLogger.logDownloadFailed(asset, reason)
            SyncDiagnostics.logDownloadFailed(asset, reason)
            return@withContext null
        }

        val localFile = getLocalFile(asset)
        val cached = findCachedFileForAsset(asset)
        if (cached != null && isCachedAssetValid(cached, asset.fileSize)) {
            CacheDownloadLogger.logCacheHit(asset, cached)
            SyncDiagnostics.logDownloadSuccess(asset, cached)
            return@withContext cached
        }

        val sourceUrl = asset.remoteSourceUrl()
        if (sourceUrl.isNullOrBlank()) {
            val reason = "missing downloadUrl and url"
            CacheDownloadLogger.logDownloadFailed(asset, reason)
            SyncDiagnostics.logDownloadFailed(asset, reason)
            return@withContext null
        }

        val downloaded = try {
            retryOnNetworkFailure(
                endpoint = "download ${asset.name}",
                maxAttempts = 3
            ) {
                downloadToCache(localFile, sourceUrl, asset.fileSize)
                    ?: throw java.io.IOException("HTTP download failed for ${asset.name}")
            }
        } catch (e: Exception) {
            CacheDownloadLogger.logDownloadFailed(asset, e.message ?: "download failed")
            SyncDiagnostics.logDownloadFailed(asset, e.message ?: "download failed")
            return@withContext null
        }

        CacheDownloadLogger.logDownloadCompleted(asset, downloaded)
        SyncDiagnostics.logDownloadSuccess(asset, downloaded)
        downloaded
    }

    private fun isCachedAssetValid(localFile: File, expectedSize: Int): Boolean {
        if (!localFile.exists() || localFile.length() == 0L) return false
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
     * Downloads playlist assets not yet on disk. Skips assets already cached (playlist change reuse).
     */
    suspend fun downloadAllAssets(
        assets: List<AssetInfo>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): Map<String, File> {
        val result = mutableMapOf<String, File>()

        val downloadableAssets = assets.filter { asset ->
            asset.assetTypeRequiresDownload() && !isAssetCached(asset)
        }

        for ((index, asset) in downloadableAssets.withIndex()) {
            val file = downloadAsset(asset)
            if (file != null) {
                result[asset.id] = file
            } else {
                findCachedFileForAsset(asset)?.let { existing ->
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

    fun getLocalFile(asset: AssetInfo): File {
        return File(cacheDir, "${asset.cacheKey()}.${asset.fileExtension()}")
    }

    private fun AssetInfo.fileExtension(): String = when (normalizedType()) {
        AssetType.URL, AssetType.HTML -> "html"
        AssetType.VIDEO -> when {
            mimeType.contains("webm", ignoreCase = true) -> "webm"
            mimeType.contains("mp4", ignoreCase = true) -> "mp4"
            else -> name.substringAfterLast('.', "mp4")
        }
        AssetType.IMAGE -> when {
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("jpeg", ignoreCase = true) ||
                mimeType.contains("jpg", ignoreCase = true) -> "jpg"
            mimeType.contains("gif", ignoreCase = true) -> "gif"
            else -> name.substringAfterLast('.', "jpg")
        }
        else -> name.substringAfterLast('.', "bin")
    }

    fun isAssetCached(asset: AssetInfo): Boolean {
        val file = findCachedFileForAsset(asset) ?: return false
        return isCachedAssetValid(file, asset.fileSize)
    }

    fun findCachedFileForAsset(asset: AssetInfo): File? {
        val canonical = getLocalFile(asset)
        if (canonical.exists() && canonical.length() > 0L) return canonical
        return findLegacyCachedFile(asset)
    }

    fun countCacheFilesOnDisk(): Int = contentCacheManager.countCacheFiles()

    private fun findLegacyCachedFile(asset: AssetInfo): File? {
        val extension = asset.fileExtension()
        val exactName = "${asset.id}.$extension"
        val hashedPrefix = "${asset.id}_"
        return cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.filter { file ->
                file.name == exactName ||
                    (file.name.startsWith(hashedPrefix) && file.name.endsWith(".$extension"))
            }
            ?.firstOrNull { it.length() > 0L }
    }

    /** Removes files not referenced by the current playlist asset IDs. */
    suspend fun cleanupStaleCache(currentAssetIds: Set<String>) = withContext(Dispatchers.IO) {
        contentCacheManager.cleanupUnreferencedFiles(currentAssetIds)
    }

    fun getCacheKey(asset: AssetInfo): String = asset.cacheKey()

    private fun AssetInfo.cacheKey(): String = id
}
