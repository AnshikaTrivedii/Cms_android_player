package com.orion.player.data.recovery

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.orion.player.MainActivity

/**
 * Single entry point for bringing the player Activity to the foreground.
 *
 * Everything that can start the player (boot receiver, crash handler, watchdog, remote
 * restart, task-removal) goes through here so that:
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

        // First boot attempt: Android starts the default Home app itself. Skip the
        // extra startActivity so we do not race the system launcher. Retries still
        // dispatch if MainActivity never becomes visible.
        if (attempt == 1 && source.startsWith("boot.") && isDefaultHomeApp(context)) {
            AutoStartLogger.autoLaunchStart(source, attempt)
            AutoStartLogger.homeRoleDeferredToSystem()
            pendingLaunchSource = source
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

    /** Called from [MainActivity.onStart] — the only proof that the player really launched. */
    fun onPlayerVisible(context: Context, fallbackSource: String) {
        playerVisible = true
        val store = BootStateStore.from(context)
        val source = pendingLaunchSource ?: fallbackSource.takeIf { store.bootLaunchPending }
        if (source != null) {
            AutoStartLogger.autoLaunchSuccess(source)
        }
        pendingLaunchSource = null
        store.clearBootLaunchPending()
        store.markPlayerRunning()
    }

    fun onPlayerHidden() {
        playerVisible = false
    }

    /** Whether Orion is currently the device's default Home app. */
    fun isDefaultHomeApp(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull() ?: return false
        return resolved.activityInfo?.packageName == context.packageName
    }

    fun logLaunchPrivileges(context: Context) {
        val overlayGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        val fullScreenIntentAllowed = if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.canUseFullScreenIntent() == true
            }.getOrDefault(false)
        } else {
            true
        }
        AutoStartLogger.launchPrivileges(
            sdk = Build.VERSION.SDK_INT,
            overlayGranted = overlayGranted,
            defaultHome = isDefaultHomeApp(context),
            deviceOwner = KioskController.isDeviceOwner(context),
            fullScreenIntentAllowed = fullScreenIntentAllowed
        )
    }

    private const val MIN_DISPATCH_GAP_MS = 3_000L
}
