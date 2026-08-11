package com.orion.player.data.config

import android.util.Log

/**
 * Diagnostics for device playback-duration settings.
 * Filter: adb logcat -s OrionDeviceConfig
 */
object DevicePlaybackDurationLogger {
    private const val TAG = "OrionDeviceConfig"

    fun receivedJson(source: String, json: String) {
        Log.i(TAG, "E2E STEP3 received JSON source=$source payload=$json")
    }

    fun settingsDownloaded(durations: DevicePlaybackDurations, source: String) {
        Log.i(
            TAG,
            "Downloaded Device Settings source=$source " +
                "Image Duration = ${durations.imageSeconds} " +
                "Document Duration = ${durations.documentSeconds} " +
                "URL Duration = ${durations.urlSeconds} " +
                "Video Duration = ${durations.videoSeconds ?: "natural-end"}"
        )
    }

    fun settingsStored(durations: DevicePlaybackDurations) {
        Log.i(
            TAG,
            "E2E STEP4 local storage written " +
                "defaultImageDuration=${durations.imageSeconds} " +
                "defaultDocumentDuration=${durations.documentSeconds} " +
                "defaultUrlDuration=${durations.urlSeconds} " +
                "defaultVideoDuration=${durations.videoSeconds ?: "null"}"
        )
    }

    fun settingsUpdated(previous: DevicePlaybackDurations, next: DevicePlaybackDurations) {
        Log.i(
            TAG,
            "Device Settings Updated " +
                "image ${previous.imageSeconds}->${next.imageSeconds} " +
                "document ${previous.documentSeconds}->${next.documentSeconds} " +
                "url ${previous.urlSeconds}->${next.urlSeconds} " +
                "video ${previous.videoSeconds ?: "null"}->${next.videoSeconds ?: "null"}"
        )
    }

    fun cacheDurationStored(assetName: String, assetType: String, durationSeconds: Int?) {
        Log.i(
            TAG,
            "Cache duration stored type=$assetType name=$assetName " +
                "durationSeconds=${durationSeconds ?: "NULL"}"
        )
    }

    fun playbackDurationApplied(
        assetType: String,
        assetName: String,
        playlistDurationSec: Int?,
        deviceDefaultSec: Int?,
        appliedSec: Long,
        source: String
    ) {
        val durationSource = when (source) {
            "playlist" -> "PLAYLIST_OVERRIDE"
            "device_default" -> "DEVICE_DEFAULT"
            "video_natural_end" -> "VIDEO_NATURAL_END"
            else -> source.uppercase()
        }
        Log.i(
            TAG,
            "Asset: $assetName ($assetType)\n" +
                "Playlist Duration: ${playlistDurationSec ?: "NULL"}\n" +
                "Device Default: ${deviceDefaultSec ?: "NULL"}\n" +
                "Applied Duration: ${if (appliedSec > 0) "$appliedSec" else "natural end"}\n" +
                "Source: $durationSource"
        )
    }
}
