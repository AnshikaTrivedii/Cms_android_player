package com.orion.player.data.remote

/**
 * Normalized asset types returned by /player/sync.
 */
object AssetType {
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val HTML = "HTML"
    const val DOCUMENT = "DOCUMENT"
    const val URL = "URL"

    fun AssetInfo.normalizedType(): String = type.uppercase()

    fun AssetInfo.requiresDownload(): Boolean = normalizedType() != DOCUMENT

    /** Playback requires a local cached file — no network during playback. */
    fun AssetInfo.isPlayable(localFiles: Map<String, java.io.File>): Boolean {
        val file = localFiles[id] ?: return false
        return file.exists() && file.length() > 0L
    }

    /** VIDEO/HTML/URL start PoP when content is actually ready, not at slot assignment. */
    fun AssetInfo.deferPopStartUntilReady(): Boolean =
        normalizedType() in setOf(VIDEO, HTML, URL)
}
