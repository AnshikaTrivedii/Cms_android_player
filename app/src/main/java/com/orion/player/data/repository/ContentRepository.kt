package com.orion.player.data.repository

import android.content.Context
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.data.remote.SyncResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for content synchronization and asset caching.
 * Downloads assets to internal cache and provides local file paths for playback.
 */
@Singleton
class ContentRepository @Inject constructor(
    private val api: OrionPlayerApi,
    private val securePrefs: SecurePrefs,
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
        val token = securePrefs.getBearerToken() ?: return null
        return api.syncPlaylist(token)
    }

    /**
     * Downloads an asset to local cache if not already cached.
     * Returns the local File path, or null on failure.
     */
    suspend fun downloadAsset(asset: AssetInfo): File? = withContext(Dispatchers.IO) {
        val localFile = getLocalFile(asset)

        // Skip if already cached with correct size
        if (localFile.exists() && localFile.length() == asset.fileSize.toLong()) {
            return@withContext localFile
        }

        val downloadUrl = asset.downloadUrl ?: return@withContext null

        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) return@withContext null

            response.body?.byteStream()?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            localFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Downloads all assets from a sync response that aren't already cached.
     * Returns a map of asset ID → local File.
     */
    suspend fun downloadAllAssets(assets: List<AssetInfo>): Map<String, File> {
        val result = mutableMapOf<String, File>()

        for (asset in assets) {
            val file = downloadAsset(asset)
            if (file != null) {
                result[asset.id] = file
            }
        }

        return result
    }

    /**
     * Returns the local file path for a given asset.
     */
    fun getLocalFile(asset: AssetInfo): File {
        // Include mutable metadata so updated assets don't reuse stale cached files.
        val extension = asset.name.substringAfterLast('.', "bin")
        return File(cacheDir, "${asset.cacheKey()}.$extension")
    }

    /**
     * Checks if an asset is already cached locally.
     */
    fun isAssetCached(asset: AssetInfo): Boolean {
        val file = getLocalFile(asset)
        return file.exists() && file.length() == asset.fileSize.toLong()
    }

    /**
     * Cleans up cached files that are no longer in the current playlist.
     */
    suspend fun cleanupStaleCache(currentCacheKeys: Set<String>) = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { file ->
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
            fileSize.toString()
        ).joinToString("|")

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)

        return "${id}_$hash"
    }
}
