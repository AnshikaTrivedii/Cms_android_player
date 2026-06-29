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

    fun AssetInfo.requiresDownload(): Boolean = normalizedType() != DOCUMENT

    /** Live or file source URL for download during sync. */
    fun AssetInfo.remoteSourceUrl(): String? =
        UrlSecurityUtil.normalizeUrl(url) ?: UrlSecurityUtil.normalizeUrl(downloadUrl)

    /** Playback requires a local cached file — no network during playback. */
    fun AssetInfo.isPlayable(localFiles: Map<String, java.io.File>): Boolean {
        val file = localFiles[id] ?: return false
        return file.exists() && file.length() > 0L
    }

    /** VIDEO/HTML/URL start PoP when content is actually ready, not at slot assignment. */
    fun AssetInfo.deferPopStartUntilReady(): Boolean =
        normalizedType() in setOf(VIDEO, HTML, URL)

    /**
     * Whether asset content changed in ways that require re-download.
     * Presigned [AssetInfo.downloadUrl] values are excluded — they rotate every sync.
     */
    fun AssetInfo.hasContentChangedFrom(other: AssetInfo): Boolean =
        name != other.name ||
            normalizedType() != other.normalizedType() ||
            mimeType != other.mimeType ||
            durationSeconds != other.durationSeconds ||
            position != other.position ||
            fileSize != other.fileSize ||
            url != other.url

    fun List<AssetInfo>.hasSyncContentChangedFrom(previous: List<AssetInfo>): Boolean {
        if (size != previous.size) return true
        if (map { it.id }.toSet() != previous.map { it.id }.toSet()) return true
        val previousById = previous.associateBy { it.id }
        return any { asset ->
            val prior = previousById[asset.id] ?: return@any true
            asset.hasContentChangedFrom(prior)
        }
    }
}
