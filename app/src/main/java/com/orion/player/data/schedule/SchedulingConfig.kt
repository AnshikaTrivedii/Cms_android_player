package com.orion.player.data.schedule

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary master switch for schedule-driven playback.
 *
 * Set [ENABLED] to `true` to restore scheduling without putting any of this
 * package back. Classes, API models, local prefs, and the assigned-playlist
 * fallback file stay in place; only execution is gated.
 */
object SchedulingConfig {
    /**
     * Temporary: scheduling must not affect playback.
     * Flip to `true` to re-enable schedule start / end / override.
     */
    const val ENABLED = false

    private const val TAG = "OrionSchedule"
    private val bannerLogged = AtomicBoolean(false)
    private val loggedWhere = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun logDisabled(where: String) {
        if (ENABLED) return
        if (bannerLogged.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "SCHEDULING_DISABLED scheduling is temporarily off — " +
                    "only the manually assigned playlist is used"
            )
        }
        if (loggedWhere.add(where)) {
            Log.i(TAG, "SCHEDULING_DISABLED where=$where")
        }
    }
}
