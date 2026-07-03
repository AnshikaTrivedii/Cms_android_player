package com.orion.player.data.remote

import com.orion.player.util.UrlSecurityUtil

/**
 * Normalized asset types returned by /player/sync.
 */
object AssetType {
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val HTML = "HTML"
    const val DOCUMENT = "DOCUMENT"
    const val URL = "URL"

    fun AssetInfo.normalizedType(): String = when (type.uppercase()) {
        "WEBSITE", "WEB", "LINK" -> URL
        else -> type.uppercase()
    }

    fun AssetInfo.requiresDownload(): Boolean = when (normalizedType()) {
        URL -> remoteSourceUrl() != null
        else -> true
    }

    /** Live or file source URL for download during sync. */
    fun AssetInfo.remoteSourceUrl(): String? =
        UrlSecurityUtil.normalizeUrl(url) ?: UrlSecurityUtil.normalizeUrl(downloadUrl)

    /** Playback requires a local cached file, or a valid remote URL for URL assets. */
    fun AssetInfo.isPlayable(localFiles: Map<String, java.io.File>): Boolean {
        return when (normalizedType()) {
            URL -> {
                val file = localFiles[id]
                (file != null && file.exists() && file.length() > 0L) ||
                    remoteSourceUrl() != null
            }
            else -> {
                val file = localFiles[id] ?: return false
                file.exists() && file.length() > 0L
            }
        }
    }

    /** VIDEO/HTML/URL/DOCUMENT start PoP when content is actually ready, not at slot assignment. */
    fun AssetInfo.deferPopStartUntilReady(): Boolean =
        normalizedType() in setOf(VIDEO, HTML, URL, DOCUMENT)

    /**
     * Whether the playlist manifest changed (order, duration, asset version, etc.).
     * Presigned [AssetInfo.downloadUrl] values are excluded — they rotate every sync.
     */
    fun AssetInfo.hasContentChangedFrom(other: AssetInfo): Boolean =
        !playlistManifestEquals(other)

    fun AssetInfo.playlistManifestEquals(other: AssetInfo): Boolean =
        id == other.id &&
            name == other.name &&
            normalizedType() == other.normalizedType() &&
            mimeType == other.mimeType &&
            durationSeconds == other.durationSeconds &&
            position == other.position &&
            fileSize == other.fileSize &&
            url == other.url &&
            assetVersion == other.assetVersion &&
            contentHash == other.contentHash

    fun List<AssetInfo>.hasSyncContentChangedFrom(previous: List<AssetInfo>): Boolean =
        playlistManifestChangedFrom(previous)

    fun List<AssetInfo>.playlistManifestChangedFrom(previous: List<AssetInfo>): Boolean {
        if (size != previous.size) return true
        if (map { it.id } != previous.map { it.id }) return true
        val previousById = previous.associateBy { it.id }
        return any { asset ->
            val prior = previousById[asset.id] ?: return@any true
            !asset.playlistManifestEquals(prior)
        }
    }
}
