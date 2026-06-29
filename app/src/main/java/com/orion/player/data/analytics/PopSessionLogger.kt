package com.orion.player.data.analytics

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Structured Proof-of-Play session diagnostics.
 * Filter logcat: adb logcat -s OrionPoP
 */
object PopSessionLogger {
    private const val TAG = "OrionPoP"
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun logSessionStarted(session: PlaybackSession) {
        Log.i(
            TAG,
            "Session START: id=${session.sessionId} asset=${session.assetName} " +
                "assetId=${session.assetId} playlist=${session.playlistName} " +
                "slotStart=${formatTime(session.slotStartTime)} " +
                "configuredDuration=${session.configuredDurationSeconds}s"
        )
    }

    fun logContentReady(session: PlaybackSession, readyTime: Instant) {
        Log.i(
            TAG,
            "Session READY: id=${session.sessionId} asset=${session.assetName} " +
                "readyAt=${formatTime(readyTime)}"
        )
    }

    fun logSessionEnded(
        session: PlaybackSession,
        startTime: Instant,
        endTime: Instant,
        durationSeconds: Int,
        status: String
    ) {
        Log.i(
            TAG,
            "Session END: id=${session.sessionId} asset=${session.assetName} " +
                "status=$status start=${formatTime(startTime)} end=${formatTime(endTime)} " +
                "duration=${durationSeconds}s"
        )
    }

    private fun formatTime(instant: Instant): String = timeFormatter.format(instant)
}
