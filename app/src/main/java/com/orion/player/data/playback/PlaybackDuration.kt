package com.orion.player.data.playback

import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType

/** CMS supplied an explicit duration (seconds). */
fun AssetInfo.hasExplicitDuration(): Boolean =
    (cmsDurationSeconds ?: 0) > 0

/**
 * Slot duration for the playback engine.
 * - Images/HTML/URL/Documents: always use configured duration (default 10s when omitted).
 * - Video with explicit duration: cap at configured seconds.
 * - Video without explicit duration: 0 → play until ExoPlayer ENDED.
 */
fun AssetInfo.playbackSlotDurationMs(): Long = when (normalizedType()) {
    AssetType.VIDEO -> if (hasExplicitDuration()) {
        cmsDurationSeconds!!.coerceAtLeast(1) * 1000L
    } else {
        0L
    }
    else -> (cmsDurationSeconds ?: 10).coerceAtLeast(1) * 1000L
}

fun AssetInfo.queuePositionLabel(): String = position.toString()
