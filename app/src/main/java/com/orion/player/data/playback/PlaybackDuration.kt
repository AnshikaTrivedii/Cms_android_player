package com.orion.player.data.playback

import com.orion.player.data.config.DevicePlaybackDurationLogger
import com.orion.player.data.config.DevicePlaybackDurations
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType

/**
 * True when the playlist slot carries an explicit positive duration that must
 * override the device default. NULL (or ≤ 0) means "no playlist override" and
 * resolves against device settings at playback time — never invent 10/15/20.
 */
fun AssetInfo.hasExplicitDuration(): Boolean =
    (cmsDurationSeconds ?: 0) > 0

enum class DurationSource {
    PLAYLIST_OVERRIDE,
    DEVICE_DEFAULT,
    VIDEO_NATURAL_END
}

data class ResolvedPlaybackDuration(
    val durationMs: Long,
    val durationSeconds: Int,
    val source: DurationSource
)

/**
 * Device default for [type], or null when the type has no configured default
 * (video only, meaning "play to natural end").
 */
fun DevicePlaybackDurations.defaultFor(type: String): Int? = when (type) {
    AssetType.VIDEO -> videoSeconds
    AssetType.DOCUMENT -> documentSeconds
    AssetType.URL -> urlSeconds
    else -> imageSeconds
}

/**
 * Resolve slot duration for every asset type with one rule:
 * playlist duration if set, else the device default for that type.
 *
 * Video is the only type that can fall through to natural end, and only when
 * the CMS has never sent a video default.
 */
fun AssetInfo.resolvePlaybackDuration(
    defaults: DevicePlaybackDurations,
    log: Boolean = true
): ResolvedPlaybackDuration {
    val type = normalizedType()
    val playlistRaw = cmsDurationSeconds?.takeIf { it > 0 }
    val deviceDefault = defaults.defaultFor(type)
    val resolved = when {
        playlistRaw != null -> {
            val seconds = playlistRaw.coerceAtLeast(1)
            ResolvedPlaybackDuration(
                durationMs = seconds * 1000L,
                durationSeconds = seconds,
                source = DurationSource.PLAYLIST_OVERRIDE
            )
        }
        deviceDefault != null -> {
            val seconds = deviceDefault.coerceAtLeast(1)
            ResolvedPlaybackDuration(
                durationMs = seconds * 1000L,
                durationSeconds = seconds,
                source = DurationSource.DEVICE_DEFAULT
            )
        }
        else -> ResolvedPlaybackDuration(
            durationMs = 0L,
            durationSeconds = 0,
            source = DurationSource.VIDEO_NATURAL_END
        )
    }

    if (log) {
        DevicePlaybackDurationLogger.playbackDurationApplied(
            assetType = type,
            assetName = name,
            playlistDurationSec = playlistRaw,
            deviceDefaultSec = deviceDefault,
            appliedSec = if (resolved.durationMs > 0) resolved.durationSeconds.toLong() else 0L,
            source = when (resolved.source) {
                DurationSource.PLAYLIST_OVERRIDE -> "playlist"
                DurationSource.DEVICE_DEFAULT -> "device_default"
                DurationSource.VIDEO_NATURAL_END -> "video_natural_end"
            }
        )
    }
    return resolved
}

fun AssetInfo.playbackSlotDurationMs(
    defaults: DevicePlaybackDurations = DevicePlaybackDurations()
): Long = resolvePlaybackDuration(defaults, log = false).durationMs

fun AssetInfo.queuePositionLabel(): String = position.toString()
