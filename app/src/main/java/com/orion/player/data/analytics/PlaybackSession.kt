package com.orion.player.data.analytics

import java.time.Instant
import java.util.UUID

/**
 * One independent Proof-of-Play session for a single asset slot in the playlist loop.
 * A new instance is created every time an asset starts — never reused across loops.
 */
data class PlaybackSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val assetId: String,
    val assetName: String,
    val playlistName: String,
    val configuredDurationSeconds: Int,
    val slotStartTime: Instant,
    var contentReadyTime: Instant? = null,
    var finalized: Boolean = false
) {
    /** When content became ready (video/html/url), or slot assignment for images. */
    fun effectiveStartTime(): Instant = contentReadyTime ?: slotStartTime

    fun effectiveEndTime(status: String): Instant {
        val start = effectiveStartTime()
        return if (status == "VERIFIED") {
            start.plusSeconds(configuredDurationSeconds.toLong())
        } else {
            start
        }
    }

    fun effectiveDurationSeconds(status: String): Int =
        if (status == "VERIFIED") configuredDurationSeconds.coerceAtLeast(1) else 0
}
