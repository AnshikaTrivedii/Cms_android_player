package com.orion.player.data.analytics

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Proof-of-Play pipeline counters for long-run diagnostics.
 * Filter: adb logcat -s OrionPoP
 */
object PopTelemetryLogger {
    private const val TAG = "OrionPoP"

    private val generatedEvents = AtomicLong(0)
    private val uploadedEvents = AtomicLong(0)
    private val failedUploadEvents = AtomicLong(0)

    fun logSchedulerStarted(hardwareId: String, intervalMs: Long) {
        Log.i(TAG, "Flush scheduler active hardwareId=$hardwareId intervalMs=$intervalMs")
    }

    fun logConfigUpdate(enabled: Boolean, popLogsExpected: Boolean?, proofOfPlay: Boolean?) {
        Log.i(
            TAG,
            "Server config: popEnabled=$enabled popLogsExpected=$popLogsExpected proofOfPlay=$proofOfPlay"
        )
    }

    fun logDisabled(reason: String) {
        Log.w(TAG, "PoP collection disabled: $reason")
    }

    fun logSkipped(reason: String) {
        Log.i(TAG, "PoP skipped: $reason")
    }

    fun logSubmitAttempt(
        hardwareId: String,
        tokenPrefix: String,
        pendingCount: Int,
        batchSize: Int
    ) {
        Log.i(
            TAG,
            "Submit attempt hardwareId=$hardwareId tokenPrefix=$tokenPrefix pending=$pendingCount batch=$batchSize"
        )
    }

    fun logSubmitSuccess(deviceId: String?, deviceName: String?, received: Int, skipped: Int?) {
        Log.i(
            TAG,
            "Submit success deviceId=${deviceId.orEmpty()} deviceName=${deviceName.orEmpty()} " +
                "received=$received skipped=${skipped ?: 0} totalUploaded=${uploadedEvents.get()}"
        )
    }

    fun logDeviceIdMismatch(expected: String, actual: String) {
        Log.e(TAG, "Device ID mismatch: stored=$expected response=$actual — keeping queue")
    }

    fun logGenerated(assetName: String, status: String) {
        generatedEvents.incrementAndGet()
        Log.i(TAG, "Event generated: asset=$assetName status=$status total=${generatedEvents.get()}")
    }

    fun logUploaded(count: Int) {
        if (count <= 0) return
        uploadedEvents.addAndGet(count.toLong())
        Log.i(TAG, "Events uploaded: count=$count total=${uploadedEvents.get()}")
    }

    fun logUploadFailed(attempted: Int, reason: String) {
        failedUploadEvents.addAndGet(attempted.toLong())
        Log.w(TAG, "Upload failed: attempted=$attempted reason=$reason totalFailed=${failedUploadEvents.get()}")
    }

    fun logQueueStatus(pending: Int) {
        Log.i(
            TAG,
            "Queue status: pending=$pending generated=${generatedEvents.get()} " +
                "uploaded=${uploadedEvents.get()} failed=${failedUploadEvents.get()}"
        )
    }
}
