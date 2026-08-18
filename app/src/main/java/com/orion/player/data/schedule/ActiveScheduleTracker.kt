package com.orion.player.data.schedule

import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.ActiveScheduleInfo
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleSyncSignal(
    val shouldSync: Boolean,
    val reason: String? = null
) {
    companion object {
        val None = ScheduleSyncSignal(shouldSync = false)
    }
}

/**
 * Follows the schedule the CMS reports as active and turns transitions into
 * ordinary sync triggers. The player never picks among schedules itself.
 *
 * A received schedule is treated as active only while
 * startDateTime <= currentServerTime < endDateTime. Once the end is reached the
 * player marks it COMPLETED/EXPIRED locally, stops using that playlist as the
 * scheduled playlist, and asks the CMS for the next schedule or the assigned one.
 *
 * Schedule state is committed only once the new playlist is actually playing, so
 * a failed download leaves the previous playlist running and the transition is
 * retried on the next sync.
 */
@Singleton
class ActiveScheduleTracker @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val expiryController: ScheduleExpiryController
) {
    /** Schedule carried by the most recent full sync payload, pending activation. */
    private var inFlightSchedule: ActiveScheduleInfo? = null
    private val lastLoggedBySource = mutableMapOf<String, String>()
    private var lastSignalledKey: String? = null
    private var lastSignalledAtMs: Long = 0L
    private var lastExpirySignalAtMs: Long = 0L
    private var lastExpiringLogAtMs: Long = 0L
    private var pendingStartRaw: String? = null

    val activeScheduleId: String? get() = securePrefs.activeScheduleId

    init {
        if (!SchedulingConfig.ENABLED) {
            SchedulingConfig.logDisabled("ActiveScheduleTracker.init")
            inFlightSchedule = null
            expiryController.cancel()
            clearCommittedSchedule(keepPlaylistName = true)
            securePrefs.expiredScheduleId = null
            securePrefs.expiredSchedulePlaylistId = null
            securePrefs.scheduleCompletionPending = false
        }
    }

    private fun disabled(where: String): Boolean {
        if (SchedulingConfig.ENABLED) return false
        SchedulingConfig.logDisabled(where)
        return true
    }

    /**
     * Evaluate a lightweight payload (revision poll / heartbeat).
     * Returns whether the schedule state requires a full sync.
     */
    fun observe(
        schedule: ActiveScheduleInfo?,
        source: String,
        serverTime: String? = null
    ): ScheduleSyncSignal {
        if (disabled("observe.$source")) return ScheduleSyncSignal.None
        serverTime?.let { ScheduleClock.noteServerTime(it) }
        val live = liveSchedule(schedule, source)
        logReceived(source, live, incomingRaw = schedule)

        val storedScheduleId = storedLiveScheduleId()
        val storedPlaylistId = if (storedScheduleId == null) null else securePrefs.activeSchedulePlaylistId
        val incomingScheduleId = live?.scheduleId?.takeIf { it.isNotBlank() }
        val incomingPlaylistId = live?.playlistId?.takeIf { it.isNotBlank() }

        val unchanged = incomingScheduleId == storedScheduleId &&
            (incomingPlaylistId == null || incomingPlaylistId == storedPlaylistId)
        if (unchanged) {
            lastSignalledKey = null
            if (live != null) {
                persistWindow(live)
                armExpiry(live)
            }
            if (securePrefs.scheduleCompletionPending && incomingScheduleId == null) {
                return signalled(REASON_ENDED)
            }
            return ScheduleSyncSignal.None
        }

        val key = "$incomingScheduleId|$incomingPlaylistId"
        if (withinCooldown(key)) return ScheduleSyncSignal.None
        rememberSignal(key)

        val reason = when {
            storedScheduleId == null && incomingScheduleId != null -> REASON_STARTED
            incomingScheduleId == null -> REASON_ENDED
            else -> REASON_CHANGED
        }
        if (reason == REASON_ENDED) {
            markExpired(
                scheduleId = securePrefs.activeScheduleId ?: storedScheduleId,
                playlistId = securePrefs.activeSchedulePlaylistId,
                start = securePrefs.activeScheduleStart,
                end = securePrefs.activeScheduleEnd,
                source = source
            )
        }
        return ScheduleSyncSignal(shouldSync = true, reason = reason)
    }

    /**
     * Record the schedule attached to a full sync payload. The manifest in that same
     * payload is the CMS's answer for this schedule, so it is remembered until the
     * playlist actually reaches the screen.
     */
    fun onSyncResponse(
        schedule: ActiveScheduleInfo?,
        syncPlaylistId: String?,
        syncPlaylistName: String?,
        serverTime: String? = null
    ) {
        if (disabled("onSyncResponse")) return
        serverTime?.let { ScheduleClock.noteServerTime(it) }
        val incoming = liveSchedule(schedule, "sync")
        logReceived("sync", incoming, incomingRaw = schedule)
        inFlightSchedule = incoming

        val scheduledPlaylistId = incoming?.playlistId?.takeIf { it.isNotBlank() }
        if (scheduledPlaylistId != null &&
            !syncPlaylistId.isNullOrBlank() &&
            scheduledPlaylistId != syncPlaylistId
        ) {
            ScheduleLogger.playlistMismatch(scheduledPlaylistId, syncPlaylistId)
        }

        val scheduleId = incoming?.scheduleId?.takeIf { it.isNotBlank() }
        val switching = scheduleId != storedLiveScheduleId() ||
            (scheduledPlaylistId != null && scheduledPlaylistId != securePrefs.activeSchedulePlaylistId)
        if (switching) {
            ScheduleLogger.playbackSwitch(
                from = securePrefs.activePlaylistName,
                to = syncPlaylistName?.takeIf { it.isNotBlank() } ?: incoming?.playlistName,
                reason = if (incoming == null) "SCHEDULE_ENDED" else "SCHEDULE_CHANGED"
            )
        }
        ScheduleLogger.reconcile(
            scheduleId = scheduleId,
            nextPlaylistId = scheduledPlaylistId ?: syncPlaylistId
        )
        if (incoming != null) {
            armExpiry(incoming)
        } else {
            expiryController.cancel()
        }
    }

    fun onAssetsDownloaded(downloaded: Int, playlistAssetCount: Int) {
        if (disabled("onAssetsDownloaded")) return
        if (inFlightSchedule == null && securePrefs.activeScheduleId == null) return
        ScheduleLogger.assetsDownloaded(
            scheduleId = inFlightSchedule?.scheduleId,
            downloaded = downloaded,
            playlistAssetCount = playlistAssetCount
        )
    }

    fun onSwitchFailed(reason: String) {
        if (disabled("onSwitchFailed")) return
        if (inFlightSchedule == null && securePrefs.activeScheduleId == null) return
        ScheduleLogger.switchFailed(inFlightSchedule?.scheduleId, reason)
    }

    /**
     * Called once a synced playlist is live on screen. Commits the schedule identity
     * so the transition is not repeated, and records the playlist name used as
     * "Previous Playlist" in the next switch log.
     */
    fun onPlaylistActivated(playlistId: String?, playlistName: String?) {
        if (disabled("onPlaylistActivated")) {
            val name = playlistName?.takeIf { it.isNotBlank() }
            if (name != null) securePrefs.activePlaylistName = name
            return
        }
        val activePlaylistId = playlistId?.takeIf { it.isNotBlank() } ?: return
        val schedule = inFlightSchedule?.takeIf { liveSchedule(it, "activate") != null }
        val scheduleId = schedule?.scheduleId?.takeIf { it.isNotBlank() }
        val schedulePlaylistId = schedule?.playlistId?.takeIf { it.isNotBlank() } ?: activePlaylistId
        val unchanged = if (scheduleId == null) {
            securePrefs.activeScheduleId == null
        } else {
            securePrefs.activeScheduleId == scheduleId &&
                securePrefs.activeSchedulePlaylistId == schedulePlaylistId
        }

        securePrefs.activePlaylistName = playlistName.orEmpty()
        if (unchanged) {
            if (scheduleId != null) {
                persistWindow(schedule)
                armExpiry(schedule)
            }
            if (scheduleId == null &&
                activePlaylistId != securePrefs.expiredSchedulePlaylistId
            ) {
                securePrefs.scheduleCompletionPending = false
            }
            return
        }

        if (scheduleId != null) {
            securePrefs.activeScheduleId = scheduleId
            securePrefs.activeSchedulePlaylistId = schedulePlaylistId
            persistWindow(schedule)
            securePrefs.scheduleCompletionPending = false
            securePrefs.expiredScheduleId = null
            securePrefs.expiredSchedulePlaylistId = null
            armExpiry(schedule)
            ScheduleLogger.active(
                scheduleId = scheduleId,
                playlistId = schedulePlaylistId,
                startTime = schedule?.resolvedStart ?: schedule?.startDateTime,
                endTime = schedule?.resolvedEnd ?: schedule?.endDateTime
            )
            ScheduleLogger.clockSnapshot(
                startRaw = schedule?.resolvedStart,
                endRaw = schedule?.resolvedEnd,
                calculatedStatus = "ACTIVE"
            )
        } else {
            clearCommittedSchedule(keepPlaylistName = true)
            if (activePlaylistId != securePrefs.expiredSchedulePlaylistId) {
                securePrefs.scheduleCompletionPending = false
                securePrefs.expiredScheduleId = null
                securePrefs.expiredSchedulePlaylistId = null
            }
            expiryController.cancel()
            ScheduleLogger.active(
                scheduleId = null,
                playlistId = activePlaylistId,
                startTime = null,
                endTime = null
            )
        }
        lastSignalledKey = null
    }

    /**
     * Cached playlist restored at startup. Schedule IDs stay as persisted unless the
     * stored window has already ended — then the expired schedule is not treated as
     * active, but cached content keeps playing until CMS sync reconciles.
     */
    fun onCachedPlaylistRestored(playlistName: String?) {
        if (disabled("onCachedPlaylistRestored")) {
            if (!playlistName.isNullOrBlank()) {
                securePrefs.activePlaylistName = playlistName
            }
            return
        }
        if (!playlistName.isNullOrBlank()) {
            securePrefs.activePlaylistName = playlistName
        }
        if (consumeLocalExpiry(source = "cache-restore")) return
        val id = securePrefs.activeScheduleId ?: return
        expiryController.arm(
            scheduleId = id,
            playlistId = securePrefs.activeSchedulePlaylistId,
            startRaw = securePrefs.activeScheduleStart,
            endRaw = securePrefs.activeScheduleEnd
        )
    }

    /**
     * Timer callback. Expires the committed schedule even if a parse edge-case
     * would otherwise leave it ACTIVE. Playback must leave this playlist.
     */
    fun onExpiryTimerFired(): Boolean {
        if (disabled("onExpiryTimerFired")) return false
        val scheduleId = securePrefs.activeScheduleId ?: inFlightSchedule?.scheduleId
        if (scheduleId.isNullOrBlank() && securePrefs.scheduleCompletionPending) return true
        if (scheduleId.isNullOrBlank()) return false
        markExpired(
            scheduleId = scheduleId,
            playlistId = securePrefs.activeSchedulePlaylistId ?: inFlightSchedule?.playlistId,
            start = securePrefs.activeScheduleStart ?: inFlightSchedule?.resolvedStart,
            end = securePrefs.activeScheduleEnd ?: inFlightSchedule?.resolvedEnd,
            source = "expiry-timer"
        )
        return true
    }

    /**
     * If the committed window has ended, stop treating it as active and ask the CMS
     * for the next schedule or the assigned playlist. Cached content is not cleared.
     */
    fun consumeLocalExpiry(source: String = "window"): Boolean {
        if (disabled("consumeLocalExpiry.$source")) return false
        val scheduleId = securePrefs.activeScheduleId ?: inFlightSchedule?.scheduleId
        if (scheduleId.isNullOrBlank() && !securePrefs.scheduleCompletionPending) return false

        val start = securePrefs.activeScheduleStart ?: inFlightSchedule?.startDateTime
        val end = securePrefs.activeScheduleEnd ?: inFlightSchedule?.endDateTime
        if (!ScheduleTime.isExpired(start, end)) return false

        val now = System.currentTimeMillis()
        if (now - lastExpirySignalAtMs < EXPIRY_RESIGNAL_MS && securePrefs.scheduleCompletionPending) {
            return false
        }
        lastExpirySignalAtMs = now

        markExpired(
            scheduleId = scheduleId,
            playlistId = securePrefs.activeSchedulePlaylistId ?: inFlightSchedule?.playlistId,
            start = start,
            end = end,
            source = source
        )
        return true
    }

    /** After network returns, force a sync if a local completion has not been confirmed. */
    fun reconcileAfterReconnect(): ScheduleSyncSignal {
        if (disabled("reconcileAfterReconnect")) return ScheduleSyncSignal.None
        if (consumeLocalExpiry(source = "reconnect")) {
            return ScheduleSyncSignal(shouldSync = true, reason = REASON_ENDED)
        }
        if (securePrefs.scheduleCompletionPending) {
            ScheduleLogger.reconcile(
                scheduleId = securePrefs.expiredScheduleId,
                nextPlaylistId = securePrefs.lastStoredPlaylistId
            )
            return ScheduleSyncSignal(shouldSync = true, reason = REASON_ENDED)
        }
        return ScheduleSyncSignal.None
    }

    fun consumeDueWindow(source: String = "window"): Boolean {
        if (disabled("consumeDueWindow.$source")) return false
        maybeLogExpiring()
        if (consumeLocalExpiry(source)) return true
        val start = pendingStartRaw ?: return false
        if (ScheduleTime.isFuture(start, null)) return false
        pendingStartRaw = null
        return true
    }

    fun maybeLogExpiring() {
        val end = securePrefs.activeScheduleEnd ?: inFlightSchedule?.endDateTime ?: return
        val remaining = ScheduleTime.millisUntil(end) ?: return
        if (remaining <= 0L || remaining > EXPIRING_WINDOW_MS) return
        val now = System.currentTimeMillis()
        if (now - lastExpiringLogAtMs < EXPIRING_LOG_COOLDOWN_MS) return
        lastExpiringLogAtMs = now
        ScheduleLogger.clockSnapshot(
            startRaw = securePrefs.activeScheduleStart ?: inFlightSchedule?.resolvedStart,
            endRaw = end,
            calculatedStatus = "ACTIVE"
        )
    }

    fun nextWindowCheckDelayMs(): Long {
        if (securePrefs.scheduleCompletionPending) return PENDING_CHECK_MS
        val candidates = listOfNotNull(
            ScheduleTime.millisUntil(securePrefs.activeScheduleEnd),
            ScheduleTime.millisUntil(securePrefs.activeScheduleStart),
            ScheduleTime.millisUntil(inFlightSchedule?.endDateTime),
            ScheduleTime.millisUntil(inFlightSchedule?.startDateTime),
            ScheduleTime.millisUntil(pendingStartRaw)
        ).filter { it > 0L }
        if (candidates.isEmpty()) return IDLE_CHECK_MS
        return (candidates.min() + 250L).coerceIn(1_000L, IDLE_CHECK_MS)
    }

    /**
     * True when CMS is still serving the playlist of a locally expired schedule
     * and has not named a new live schedule.
     */
    fun isStaleScheduledPlaylist(syncPlaylistId: String?): Boolean {
        if (disabled("isStaleScheduledPlaylist")) return false
        if (inFlightSchedule != null) return false
        val expiredPlaylistId = securePrefs.expiredSchedulePlaylistId ?: return false
        if (syncPlaylistId.isNullOrBlank() || syncPlaylistId != expiredPlaylistId) return false
        if (securePrefs.scheduleCompletionPending) return true
        return false
    }

    fun shouldPreserveCurrentAsAssigned(currentPlaylistId: String?): Boolean {
        if (disabled("shouldPreserveCurrentAsAssigned")) return false
        val currentId = currentPlaylistId?.takeIf { it.isNotBlank() } ?: return false
        val scheduledId = inFlightSchedule?.playlistId?.takeIf { it.isNotBlank() }
        if (scheduledId != null) return currentId != scheduledId
        return currentId != securePrefs.expiredSchedulePlaylistId
    }

    fun logPopEvent(
        playlistId: String?,
        assetId: String?,
        startTime: String?,
        endTime: String?,
        durationSeconds: Int,
        status: String
    ) {
        if (disabled("logPopEvent")) return
        val scheduleId = inFlightSchedule?.scheduleId ?: securePrefs.activeScheduleId
        if (scheduleId.isNullOrBlank()) return
        ScheduleLogger.popEvent(
            scheduleId = scheduleId,
            playlistId = playlistId ?: securePrefs.activeSchedulePlaylistId,
            assetId = assetId,
            deviceId = deviceId(),
            serverTime = ScheduleClock.isoNow(),
            startTime = inFlightSchedule?.startDateTime
                ?: securePrefs.activeScheduleStart
                ?: startTime,
            endTime = inFlightSchedule?.endDateTime
                ?: securePrefs.activeScheduleEnd
                ?: endTime,
            durationSeconds = durationSeconds,
            status = status
        )
    }

    fun logTokenRefresh(detail: String) {
        ScheduleLogger.tokenRefresh(
            scheduleId = inFlightSchedule?.scheduleId ?: securePrefs.activeScheduleId,
            playlistId = inFlightSchedule?.playlistId ?: securePrefs.activeSchedulePlaylistId,
            deviceId = deviceId(),
            serverTime = ScheduleClock.isoNow(),
            startTime = inFlightSchedule?.startDateTime ?: securePrefs.activeScheduleStart,
            endTime = inFlightSchedule?.endDateTime ?: securePrefs.activeScheduleEnd,
            detail = detail
        )
    }

    fun clear() {
        inFlightSchedule = null
        lastLoggedBySource.clear()
        lastSignalledKey = null
        lastExpirySignalAtMs = 0L
        lastExpiringLogAtMs = 0L
        pendingStartRaw = null
        expiryController.cancel()
        clearCommittedSchedule(keepPlaylistName = false)
        securePrefs.activePlaylistName = null
        securePrefs.expiredScheduleId = null
        securePrefs.expiredSchedulePlaylistId = null
        securePrefs.scheduleCompletionPending = false
    }

    private fun liveSchedule(schedule: ActiveScheduleInfo?, source: String): ActiveScheduleInfo? {
        val incoming = schedule?.takeIf { it.isPresent } ?: return null
        if (incoming.isTerminalStatus) {
            ScheduleLogger.ignoredExpired(source, incoming.scheduleId, incoming.resolvedEnd)
            return null
        }
        return when (ScheduleTime.windowState(incoming.resolvedStart, incoming.resolvedEnd)) {
            ScheduleTime.WindowState.EXPIRED -> {
                ScheduleLogger.ignoredExpired(source, incoming.scheduleId, incoming.resolvedEnd)
                null
            }
            ScheduleTime.WindowState.FUTURE -> {
                pendingStartRaw = incoming.resolvedStart
                ScheduleLogger.ignoredFuture(source, incoming.scheduleId, incoming.resolvedStart)
                null
            }
            ScheduleTime.WindowState.ACTIVE, ScheduleTime.WindowState.UNKNOWN -> {
                pendingStartRaw = null
                incoming
            }
        }
    }

    private fun storedLiveScheduleId(): String? {
        val id = securePrefs.activeScheduleId ?: return null
        if (ScheduleTime.isExpired(securePrefs.activeScheduleStart, securePrefs.activeScheduleEnd)) {
            return null
        }
        if (ScheduleTime.isFuture(securePrefs.activeScheduleStart, securePrefs.activeScheduleEnd)) {
            return null
        }
        return id
    }

    private fun persistWindow(schedule: ActiveScheduleInfo?) {
        securePrefs.activeScheduleStart = schedule?.resolvedStart ?: schedule?.startDateTime
        securePrefs.activeScheduleEnd = schedule?.resolvedEnd ?: schedule?.endDateTime
    }

    private fun armExpiry(schedule: ActiveScheduleInfo?) {
        val live = schedule ?: return
        expiryController.arm(
            scheduleId = live.scheduleId,
            playlistId = live.playlistId,
            startRaw = live.resolvedStart,
            endRaw = live.resolvedEnd
        )
    }

    private fun markExpired(
        scheduleId: String?,
        playlistId: String?,
        start: String?,
        end: String?,
        source: String
    ) {
        expiryController.cancel()
        ScheduleLogger.clockSnapshot(
            startRaw = start,
            endRaw = end,
            calculatedStatus = "EXPIRED"
        )
        ScheduleLogger.expired(
            scheduleId = scheduleId,
            playlistId = playlistId,
            serverNow = ScheduleClock.isoNow(),
            endTime = end
        )
        inFlightSchedule = null
        if (!scheduleId.isNullOrBlank()) {
            securePrefs.expiredScheduleId = scheduleId
        }
        if (!playlistId.isNullOrBlank()) {
            securePrefs.expiredSchedulePlaylistId = playlistId
        }
        clearCommittedSchedule(keepPlaylistName = true)
        securePrefs.scheduleCompletionPending = true
        lastSignalledKey = null
    }

    private fun clearCommittedSchedule(keepPlaylistName: Boolean) {
        securePrefs.activeScheduleId = null
        securePrefs.activeSchedulePlaylistId = null
        securePrefs.activeScheduleStart = null
        securePrefs.activeScheduleEnd = null
        if (!keepPlaylistName) securePrefs.activePlaylistName = null
    }

    private fun logReceived(source: String, schedule: ActiveScheduleInfo?, incomingRaw: ActiveScheduleInfo?) {
        val key = "${schedule?.scheduleId.orEmpty()}|${schedule?.playlistId.orEmpty()}|" +
            "${schedule?.startDateTime.orEmpty()}|${schedule?.endDateTime.orEmpty()}|" +
            "${incomingRaw?.scheduleId.orEmpty()}"
        if (lastLoggedBySource[source] == key) return
        lastLoggedBySource[source] = key
        ScheduleLogger.received(
            source = source,
            schedule = schedule ?: incomingRaw?.takeIf { it.isPresent },
            deviceId = deviceId(),
            serverTime = ScheduleClock.isoNow()
        )
    }

    private fun withinCooldown(key: String): Boolean {
        val now = System.currentTimeMillis()
        return key == lastSignalledKey && now - lastSignalledAtMs < RESIGNAL_COOLDOWN_MS
    }

    private fun rememberSignal(key: String) {
        lastSignalledKey = key
        lastSignalledAtMs = System.currentTimeMillis()
    }

    private fun signalled(reason: String): ScheduleSyncSignal {
        val key = "pending|$reason"
        if (withinCooldown(key)) return ScheduleSyncSignal.None
        rememberSignal(key)
        return ScheduleSyncSignal(shouldSync = true, reason = reason)
    }

    private fun deviceId(): String =
        securePrefs.cmsDeviceId?.takeIf { it.isNotBlank() } ?: securePrefs.getOrCreateHardwareId()

    companion object {
        const val REASON_STARTED = "schedule.started"
        const val REASON_ENDED = "schedule.ended"
        const val REASON_CHANGED = "schedule.changed"

        private const val RESIGNAL_COOLDOWN_MS = 60_000L
        private const val EXPIRY_RESIGNAL_MS = 5_000L
        private const val IDLE_CHECK_MS = 15_000L
        private const val PENDING_CHECK_MS = 5_000L
        private const val EXPIRING_WINDOW_MS = 60_000L
        private const val EXPIRING_LOG_COOLDOWN_MS = 15_000L
    }
}
