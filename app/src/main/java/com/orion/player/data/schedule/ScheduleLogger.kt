package com.orion.player.data.schedule

import android.util.Log
import com.orion.player.data.remote.ActiveScheduleInfo

/**
 * Structured schedule diagnostics.
 * Filter: adb logcat -s OrionSchedule
 */
object ScheduleLogger {
    private const val TAG = "OrionSchedule"

    fun received(
        source: String,
        schedule: ActiveScheduleInfo?,
        deviceId: String?,
        serverTime: String
    ) {
        if (schedule == null || !schedule.isPresent) {
            Log.i(TAG, "SCHEDULE_RECEIVED source=$source active=NONE deviceId=${deviceId ?: "none"} serverTime=$serverTime")
            return
        }
        Log.i(
            TAG,
            "SCHEDULE_RECEIVED source=$source active=YES " +
                "scheduleId=${nz(schedule.scheduleId)} playlistId=${nz(schedule.playlistId)} " +
                "serverTime=$serverTime startTime=${nz(schedule.resolvedStart)} endTime=${nz(schedule.resolvedEnd)} " +
                "status=${nz(schedule.status)} deviceId=${deviceId ?: "none"}"
        )
    }

    fun active(
        scheduleId: String?,
        playlistId: String?,
        startTime: String?,
        endTime: String?
    ) {
        Log.i(TAG, "[SCHEDULE_ACTIVE]")
        Log.i(TAG, "scheduleId=${nz(scheduleId)}")
        Log.i(TAG, "playlistId=${nz(playlistId)}")
        Log.i(TAG, "start=${nz(startTime)}")
        Log.i(TAG, "end=${nz(endTime)}")
    }

    fun expiryTimer(
        scheduleId: String?,
        playlistId: String?,
        expiresAt: String?,
        remainingMs: Long
    ) {
        Log.i(TAG, "[SCHEDULE_EXPIRY_TIMER]")
        Log.i(TAG, "scheduleId=${nz(scheduleId)}")
        Log.i(TAG, "playlistId=${nz(playlistId)}")
        Log.i(TAG, "expiresAt=${nz(expiresAt)}")
        Log.i(TAG, "remainingMs=$remainingMs")
    }

    fun expired(
        scheduleId: String?,
        playlistId: String?,
        serverNow: String,
        endTime: String?
    ) {
        Log.i(TAG, "[SCHEDULE_EXPIRED]")
        Log.i(TAG, "scheduleId=${nz(scheduleId)}")
        Log.i(TAG, "playlistId=${nz(playlistId)}")
        Log.i(TAG, "serverNow=$serverNow")
        Log.i(TAG, "endTime=${nz(endTime)}")
    }

    fun reconcile(
        scheduleId: String?,
        nextPlaylistId: String?
    ) {
        Log.i(TAG, "[SCHEDULE_RECONCILE]")
        Log.i(TAG, "scheduleId=${nz(scheduleId)}")
        Log.i(TAG, "nextPlaylistId=${nz(nextPlaylistId)}")
    }

    fun playbackSwitch(
        from: String?,
        to: String?,
        reason: String
    ) {
        Log.i(TAG, "[PLAYLIST_SWITCH]")
        Log.i(TAG, "from=${from?.takeIf { it.isNotBlank() } ?: "none"}")
        Log.i(TAG, "to=${to?.takeIf { it.isNotBlank() } ?: "none"}")
        Log.i(TAG, "reason=$reason")
    }

    fun clockSnapshot(
        startRaw: String?,
        endRaw: String?,
        calculatedStatus: String
    ) {
        val remaining = ScheduleTime.millisUntil(endRaw)
        Log.i(
            TAG,
            "serverNow=${ScheduleClock.isoNow()} deviceNow=${ScheduleClock.deviceNow()} " +
                "scheduleStart=${nz(startRaw)} scheduleEnd=${nz(endRaw)} " +
                "timezone=${ScheduleTime.CMS_ZONE.id} remainingMs=${remaining ?: "n/a"} " +
                "calculatedStatus=$calculatedStatus clockOffsetMs=${ScheduleClock.offsetMs()}"
        )
    }

    fun tokenRefresh(
        scheduleId: String?,
        playlistId: String?,
        deviceId: String?,
        serverTime: String,
        startTime: String?,
        endTime: String?,
        detail: String
    ) {
        Log.w(
            TAG,
            "SCHEDULE_TOKEN_REFRESH detail=$detail scheduleId=${nz(scheduleId)} " +
                "playlistId=${nz(playlistId)} deviceId=${deviceId ?: "none"} serverTime=$serverTime " +
                "startTime=${nz(startTime)} endTime=${nz(endTime)} — retrying without unpairing"
        )
    }

    fun popEvent(
        scheduleId: String?,
        playlistId: String?,
        assetId: String?,
        deviceId: String?,
        serverTime: String,
        startTime: String?,
        endTime: String?,
        durationSeconds: Int,
        status: String
    ) {
        Log.i(
            TAG,
            "SCHEDULE_POP_EVENT status=$status durationSeconds=$durationSeconds assetId=${assetId ?: "unknown"} " +
                "scheduleId=${nz(scheduleId)} playlistId=${nz(playlistId)} deviceId=${deviceId ?: "none"} " +
                "serverTime=$serverTime startTime=${nz(startTime)} endTime=${nz(endTime)}"
        )
    }

    fun assetsDownloaded(scheduleId: String?, downloaded: Int, playlistAssetCount: Int) {
        Log.i(
            TAG,
            "Assets Downloaded scheduleId=${nz(scheduleId)} downloaded=$downloaded playlistAssets=$playlistAssetCount"
        )
    }

    fun switchFailed(scheduleId: String?, reason: String) {
        Log.w(
            TAG,
            "Schedule switch not completed scheduleId=${nz(scheduleId)} reason=$reason — keeping current playlist, will retry"
        )
    }

    fun playlistMismatch(expectedPlaylistId: String, receivedPlaylistId: String) {
        Log.w(
            TAG,
            "Sync payload does not match the active schedule yet " +
                "(schedule playlistId=$expectedPlaylistId, sync playlistId=$receivedPlaylistId) " +
                "— keeping current playlist until the CMS serves the scheduled playlist"
        )
    }

    fun ignoredExpired(source: String, scheduleId: String?, endTime: String?) {
        Log.i(TAG, "Ignoring expired schedule from $source scheduleId=${nz(scheduleId)} endTime=${nz(endTime)}")
    }

    fun ignoredFuture(source: String, scheduleId: String?, startTime: String?) {
        Log.i(TAG, "Ignoring future schedule from $source scheduleId=${nz(scheduleId)} startTime=${nz(startTime)}")
    }

    private fun nz(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "none"
}
