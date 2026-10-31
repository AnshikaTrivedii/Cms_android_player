package com.orion.player.data.update

/** Pure decisions shared by heartbeat, download, and post-install recovery. */
object UpdatePolicy {
    fun shouldDownload(
        offeredVersion: Int,
        installedVersion: Int,
        sameRelease: Boolean,
        state: String?,
        retryAfterMs: Long,
        nowMs: Long
    ): Boolean {
        if (offeredVersion <= installedVersion || nowMs < retryAfterMs) return false
        return !sameRelease || state !in setOf("VERIFIED", "INSTALLING", "BLOCKED", "HEALTHY")
    }

    fun isHealthy(
        state: String?,
        installedVersion: Int,
        expectedVersion: Int,
        activityVisible: Boolean,
        playbackActive: Boolean,
        slotStartedMs: Long,
        processStartedMs: Long
    ): Boolean =
        state == "INSTALLING" &&
            installedVersion == expectedVersion &&
            activityVisible &&
            playbackActive &&
            slotStartedMs >= processStartedMs
}
