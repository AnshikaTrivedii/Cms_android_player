package com.orion.player.data.cache

import android.content.Context
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.isPlayable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the on-disk content cache at [getContentDirectory].
 * Path: /data/data/<package>/files/content/
 */
@Singleton
class ContentCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CONTENT_DIR_NAME = "content"
        private const val LEGACY_DIR_NAME = "orion_assets"
        private const val MIGRATION_FLAG = "content_cache_migrated_v1"
    }

    private val prefs by lazy {
        context.getSharedPreferences("orion_cache_prefs", Context.MODE_PRIVATE)
    }

    fun getContentDirectory(): File =
        File(context.filesDir, CONTENT_DIR_NAME).also { it.mkdirs() }

    /** @deprecated Legacy path — migrated into [getContentDirectory] on first access. */
    private fun getLegacyCacheDirectory(): File =
        File(context.cacheDir, LEGACY_DIR_NAME)

    init {
        migrateLegacyCacheIfNeeded()
    }

    fun getCacheStats(): ContentCacheStats {
        val dir = getContentDirectory()
        val files = listCacheFiles(dir)
        return ContentCacheStats(
            directoryPath = dir.absolutePath,
            totalSizeBytes = files.sumOf { it.length() },
            fileCount = files.size
        )
    }

    fun listCacheFiles(): List<File> = listCacheFiles(getContentDirectory())

    fun computeTotalSizeBytes(): Long = listCacheFiles().sumOf { it.length() }

    fun countCacheFiles(): Int = listCacheFiles().size

    fun validateAssets(
        assets: List<AssetInfo>,
        localFiles: Map<String, File>
    ): CacheValidationResult {
        val missingIds = mutableListOf<String>()
        val missingNames = mutableListOf<String>()
        for (asset in assets) {
            if (!asset.isPlayable(localFiles)) {
                missingIds += asset.id
                missingNames += asset.name
                CacheDownloadLogger.logValidationMissing(asset)
            }
        }
        CacheDownloadLogger.logValidationSummary(assets.size, missingIds.size)
        return CacheValidationResult(
            totalAssets = assets.size,
            missingAssetIds = missingIds,
            missingAssetNames = missingNames
        )
    }

    fun cleanupUnreferencedFiles(keepAssetIds: Set<String>) {
        val dir = getContentDirectory()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name.endsWith(".tmp")) return@forEach
            val cacheKey = file.nameWithoutExtension
            if (cacheKey !in keepAssetIds) {
                CacheDownloadLogger.logCleanupDeleted(file)
                file.delete()
            } else {
                CacheDownloadLogger.logCleanupSkipped(file, "still referenced by current playlist")
            }
        }
        // Also prune legacy directory if anything remains
        getLegacyCacheDirectory().listFiles()?.forEach { file ->
            if (!file.isFile || file.name.endsWith(".tmp")) return@forEach
            val cacheKey = file.nameWithoutExtension
            if (cacheKey !in keepAssetIds) {
                CacheDownloadLogger.logCleanupDeleted(file)
                file.delete()
            }
        }
    }

    private fun listCacheFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") && it.length() > 0L }
            .orEmpty()

    private fun migrateLegacyCacheIfNeeded() {
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return
        val legacy = getLegacyCacheDirectory()
        val target = getContentDirectory()
        if (!legacy.exists()) {
            prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
            return
        }
        legacy.listFiles()?.forEach { file ->
            if (!file.isFile || file.name.endsWith(".tmp")) return@forEach
            val dest = File(target, file.name)
            if (!dest.exists()) {
                file.copyTo(dest, overwrite = false)
            }
        }
        prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
        CacheDownloadLogger.logCacheDirectory(
            target.absolutePath,
            computeTotalSizeBytes(),
            countCacheFiles()
        )
    }
}
