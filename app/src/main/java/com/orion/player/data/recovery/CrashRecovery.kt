package com.orion.player.data.recovery

import android.app.Application
import android.os.Process
import com.orion.player.data.enterprise.CrashLogStore
import com.orion.player.data.enterprise.DeviceLogCollector

/**
 * Captures uncaught crashes and relaunches the player without manual intervention.
 */
object CrashRecovery {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var application: Application? = null
    private var crashLogStore: CrashLogStore? = null
    private var deviceLogCollector: DeviceLogCollector? = null

    fun install(
        app: Application,
        crashStore: CrashLogStore,
        logCollector: DeviceLogCollector
    ) {
        if (defaultHandler != null) return
        application = app
        crashLogStore = crashStore
        deviceLogCollector = logCollector
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
        }
    }

    private fun handleCrash(thread: Thread, throwable: Throwable) {
        val app = application
        try {
            OrionRecoveryLogger.logCrashDetected(throwable)
            crashLogStore?.recordCrash(throwable)
            deviceLogCollector?.logCrash(
                "${throwable.javaClass.simpleName}: ${throwable.message}"
            )
            if (app != null) {
                val source = "crash.${throwable.javaClass.simpleName}"
                // The process is about to be killed, so the Activity is gone even though
                // it never got an onStop. Without this the relaunch would be suppressed as
                // a duplicate of an instance that no longer exists.
                AutoStartCoordinator.onPlayerHidden()
                // A crash that repeats immediately after every relaunch would otherwise
                // spin forever. Back off instead, and let an alarm bring the player back:
                // the cache and all persisted state survive untouched.
                val decision = RecoveryThrottle.from(app).evaluate(RecoveryThrottle.KEY_CRASH)
                if (decision.allowed) {
                    PlayerLaunchHelper.launchPlayer(app, source)
                    OrionRecoveryLogger.logRecoveryCompleted(source)
                } else {
                    AutoStartLogger.crashLoopBackoff(decision.attempt, decision.retryInMs)
                    PlayerLaunchHelper.scheduleDelayedRelaunch(app, decision.retryInMs)
                }
            }
        } catch (recoveryError: Exception) {
            OrionRecoveryLogger.logCrashDetected(recoveryError)
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        Process.killProcess(Process.myPid())
        System.exit(10)
    }
}
