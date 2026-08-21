package com.orion.player.data.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One expiry timer for the currently active schedule.
 *
 * Receiving the same schedule again does not create a second timer. A new
 * schedule (or a changed end time) cancels the old timer and arms a new one.
 */
@Singleton
class ScheduleExpiryController @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var armedScheduleId: String? = null
    private var armedEndRaw: String? = null
    private var onExpired: (() -> Unit)? = null
    private var contentChangeJob: Job? = null
    private var armedContentChangeAt: String? = null
    private var onNextContentChange: (() -> Unit)? = null

    fun setOnExpired(listener: () -> Unit) {
        onExpired = listener
    }

    fun setOnNextContentChange(listener: () -> Unit) {
        onNextContentChange = listener
    }

    /**
     * Arm a one-shot wake at CMS [nextContentChangeAt] so schedule start/end
     * does not wait for the next heartbeat. Null cancels the timer.
     */
    @Synchronized
    fun armNextContentChange(atIso: String?) {
        val at = atIso?.takeIf { it.isNotBlank() }
        if (at == null) {
            cancelNextContentChangeLocked()
            return
        }
        if (at == armedContentChangeAt && contentChangeJob?.isActive == true) {
            return
        }
        cancelNextContentChangeLocked()
        armedContentChangeAt = at
        val remaining = (ScheduleTime.millisUntil(at) ?: 0L).coerceAtLeast(0L)
        ScheduleLogger.clockSnapshot(
            startRaw = at,
            endRaw = at,
            calculatedStatus = "NEXT_CONTENT_CHANGE"
        )
        contentChangeJob = scope.launch {
            if (remaining > 0L) delay(remaining)
            onNextContentChange?.invoke()
        }
    }

    @Synchronized
    fun arm(
        scheduleId: String?,
        playlistId: String?,
        startRaw: String?,
        endRaw: String?
    ) {
        val id = scheduleId?.takeIf { it.isNotBlank() } ?: return
        val end = endRaw?.takeIf { it.isNotBlank() } ?: return

        if (id == armedScheduleId && end == armedEndRaw && job?.isActive == true) {
            return
        }

        if (!SchedulingConfig.ENABLED) {
            SchedulingConfig.logDisabled("ScheduleExpiryController.arm")
            return
        }

        cancelLocked()
        armedScheduleId = id
        armedEndRaw = end

        var remaining = ScheduleTime.millisUntil(end) ?: 0L
        if (ScheduleTime.isExpired(startRaw, end)) {
            remaining = 0L
        }
        ScheduleLogger.expiryTimer(
            scheduleId = id,
            playlistId = playlistId,
            expiresAt = end,
            remainingMs = remaining
        )
        ScheduleLogger.clockSnapshot(
            startRaw = startRaw,
            endRaw = end,
            calculatedStatus = ScheduleTime.calculatedStatus(startRaw, end, terminalCmsStatus = false)
        )

        job = scope.launch {
            if (remaining > 0L) delay(remaining)
            var extra = ScheduleTime.millisUntil(end) ?: 0L
            var guard = 0
            while (extra > 0L && !ScheduleTime.isExpired(startRaw, end) && guard < 5) {
                delay(extra.coerceAtMost(1_000L))
                extra = ScheduleTime.millisUntil(end) ?: 0L
                guard++
            }
            onExpired?.invoke()
        }
    }

    @Synchronized
    fun cancel() {
        cancelLocked()
    }

    @Synchronized
    fun cancelNextContentChange() {
        cancelNextContentChangeLocked()
    }

    private fun cancelLocked() {
        job?.cancel()
        job = null
        armedScheduleId = null
        armedEndRaw = null
    }

    private fun cancelNextContentChangeLocked() {
        contentChangeJob?.cancel()
        contentChangeJob = null
        armedContentChangeAt = null
    }
}
