package com.orion.player.data.playback

import android.util.Log
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.normalizedType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Per-asset playback slot diagnostics.
 * Filter: adb logcat -s OrionPlayback
 */
object PlaybackSlotLogger {
    private const val TAG = "OrionPlayback"
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    fun logSlotStarted(
        queueIndex: Int,
        asset: AssetInfo,
        configuredDurationMs: Long,
        sessionId: String
    ) {
        Log.i(
            TAG,
            "Slot START: index=$queueIndex position=${asset.position} " +
                "name=${asset.name} type=${asset.normalizedType()} " +
                "configuredMs=$configuredDurationMs session=$sessionId " +
                "time=${formatTime(Instant.now())}"
        )
    }

    fun logContentReady(assetName: String, sessionId: String) {
        Log.i(TAG, "Slot READY: name=$assetName session=$sessionId time=${formatTime(Instant.now())}")
    }

    fun logSlotEnded(
        queueIndex: Int,
        asset: AssetInfo,
        configuredDurationMs: Long,
        actualDurationMs: Long,
        sessionId: String,
        result: String
    ) {
        Log.i(
            TAG,
            "Slot END: index=$queueIndex position=${asset.position} name=${asset.name} " +
                "configuredMs=$configuredDurationMs actualMs=$actualDurationMs " +
                "result=$result session=$sessionId end=${formatTime(Instant.now())}"
        )
    }

    fun logGaplessHandoff(fromIndex: Int, toIndex: Int, ready: Boolean) {
        Log.i(
            TAG,
            "Slot HANDOFF: from=$fromIndex to=$toIndex " +
                if (ready) "gapless" else "slow"
        )
    }

    fun logSlotSkipped(queueIndex: Int, asset: AssetInfo, reason: String) {
        Log.w(
            TAG,
            "Slot SKIP: index=$queueIndex position=${asset.position} " +
                "name=${asset.name} reason=$reason"
        )
    }

    fun logPendingSnapshotDeferred(reason: String, currentAsset: String) {
        Log.i(TAG, "Queue update deferred: reason=$reason currentAsset=$currentAsset")
    }

    fun logPendingSnapshotApplied(assetCount: Int, resetIndex: Boolean) {
        Log.i(TAG, "Deferred queue applied: assets=$assetCount resetIndex=$resetIndex")
    }

    private fun formatTime(instant: Instant): String = timeFormatter.format(instant)
}
