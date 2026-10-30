package com.orion.player.data.recovery

import android.content.Context
import android.content.Intent
import com.orion.player.MainActivity

/**
 * Single entry point for bringing the player Activity to the foreground.
 *
 * Callers that may start the player (crash handler, playback-watchdog fallback, remote
 * restart) go through here so that:
 * - a launch is never issued while the player is already on screen (no duplicate task),
 * - concurrent callers cannot dispatch a burst of launches,
 * - `PLAYER_AUTO_LAUNCH_SUCCESS` is only logged once the Activity is genuinely visible,
 *   never merely because `startActivity` did not throw. On Android 10+ a blocked
 *   background activity start fails silently, so dispatch is not proof of launch.
 */
object AutoStartCoordinator {

    const val EXTRA_LAUNCH_SOURCE = "com.orion.player.extra.LAUNCH_SOURCE"

    @Volatile
    private var playerVisible = false

    @Volatile
    private var pendingLaunchSource: String? = null

    @Volatile
    private var lastDispatchAtMs = 0L

    /**
     * True while [MainActivity] is between onStart and onStop.
     *
     * Deliberately an in-process flag rather than an ActivityManager importance check:
     * the app is single-process, so the boot receiver, the foreground service and the
     * Activity all share this flag, and a freshly started process cannot have an Activity.
     * Process importance would be ambiguous here — a process executing a broadcast or
     * hosting a foreground service can report an importance as high as a visible one.
     */
    fun isPlayerInForeground(context: Context): Boolean = playerVisible

    /**
     * Dispatch a launch of [MainActivity]. Returns false only when the platform rejected
     * the dispatch outright; a silently blocked background start still returns true and is
     * caught later by the boot retry loop.
     */
    fun launch(context: Context, source: String, attempt: Int = 1): Boolean {
        if (isPlayerInForeground(context)) {
            AutoStartLogger.playerAlreadyRunning(source)
            return true
        }

        val now = System.currentTimeMillis()
        if (now - lastDispatchAtMs < MIN_DISPATCH_GAP_MS) {
            // Another caller just dispatched a launch that has not landed yet.
            return true
        }
        lastDispatchAtMs = now

        AutoStartLogger.autoLaunchStart(source, attempt)
        pendingLaunchSource = source

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_LAUNCH_SOURCE, source)
        }
        return try {
            context.startActivity(launchIntent)
            true
        } catch (error: Exception) {
            AutoStartLogger.autoLaunchDispatchFailed(source, error)
            false
        }
    }

    /** Called from [MainActivity.onStart] — the only proof that a requested launch landed. */
    fun onPlayerVisible(context: Context) {
        playerVisible = true
        val store = BootStateStore.from(context)
        pendingLaunchSource?.let { source ->
            AutoStartLogger.autoLaunchSuccess(source)
        }
        pendingLaunchSource = null
        store.markPlayerRunning()
    }

    fun onPlayerHidden() {
        playerVisible = false
    }

    private const val MIN_DISPATCH_GAP_MS = 3_000L
}
