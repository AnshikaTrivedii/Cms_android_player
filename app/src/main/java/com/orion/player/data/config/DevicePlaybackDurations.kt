package com.orion.player.data.config

/**
 * Device-level default durations (seconds) used when a playlist item has no
 * explicit duration. Stored separately from playlist data so playlist NULLs
 * stay NULL and resolve dynamically at playback time.
 *
 * [videoSeconds] is nullable: a CMS that never sends a video default leaves it
 * null and videos keep playing to their natural end.
 */
data class DevicePlaybackDurations(
    val imageSeconds: Int = DEFAULT_IMAGE_SECONDS,
    val documentSeconds: Int = DEFAULT_DOCUMENT_SECONDS,
    val urlSeconds: Int = DEFAULT_URL_SECONDS,
    val videoSeconds: Int? = null
) {
    companion object {
        const val DEFAULT_IMAGE_SECONDS = 10
        const val DEFAULT_DOCUMENT_SECONDS = 20
        const val DEFAULT_URL_SECONDS = 20

        /** Persisted marker for "CMS has no video default" (videos run to natural end). */
        const val VIDEO_NATURAL_END = 0

        fun sanitize(
            image: Int?,
            document: Int?,
            url: Int?,
            video: Int?
        ): DevicePlaybackDurations =
            DevicePlaybackDurations(
                imageSeconds = clamp(image, DEFAULT_IMAGE_SECONDS),
                documentSeconds = clamp(document, DEFAULT_DOCUMENT_SECONDS),
                urlSeconds = clamp(url, DEFAULT_URL_SECONDS),
                videoSeconds = clampNullable(video)
            )

        private fun clamp(value: Int?, fallback: Int): Int {
            if (value == null || value <= 0) return fallback
            return value.coerceIn(1, 3600)
        }

        private fun clampNullable(value: Int?): Int? {
            if (value == null || value <= 0) return null
            return value.coerceIn(1, 3600)
        }
    }
}
