package com.orion.player.data.telemetry

import android.util.Log
import com.google.gson.Gson
import com.orion.player.data.remote.GsonConfig
import com.orion.player.data.remote.HeartbeatRequest

/**
 * Structured heartbeat tracing for production diagnostics.
 * Filter: adb logcat -s OrionHeartbeat
 */
object HeartbeatTelemetryLogger {
    private const val TAG = "OrionHeartbeat"
    private val gson = GsonConfig.create()

    fun logScheduled(deviceId: String, intervalMs: Long) {
        Log.i(TAG, "Scheduler active deviceId=$deviceId intervalMs=$intervalMs")
    }

    fun logAttempt(
        deviceId: String,
        attempt: Int,
        url: String,
        body: HeartbeatRequest
    ) {
        Log.i(
            TAG,
            "ATTEMPT deviceId=$deviceId attempt=$attempt timestamp=${System.currentTimeMillis()} " +
                "method=POST url=$url body=${gson.toJson(body)}"
        )
    }

    fun logSuccess(
        deviceId: String,
        attempt: Int,
        responseCode: Int,
        responseBody: String,
        elapsedMs: Long
    ) {
        Log.i(
            TAG,
            "SUCCESS deviceId=$deviceId attempt=$attempt responseCode=$responseCode " +
                "elapsedMs=$elapsedMs responseBody=$responseBody"
        )
    }

    fun logFailure(
        deviceId: String,
        attempt: Int,
        responseCode: Int?,
        responseBody: String?,
        error: String,
        elapsedMs: Long
    ) {
        Log.w(
            TAG,
            "FAILURE deviceId=$deviceId attempt=$attempt responseCode=${responseCode ?: "n/a"} " +
                "elapsedMs=$elapsedMs error=$error responseBody=${responseBody.orEmpty()}"
        )
    }

    fun logSkipped(deviceId: String, reason: String) {
        Log.i(TAG, "SKIPPED deviceId=$deviceId reason=$reason")
    }
}
