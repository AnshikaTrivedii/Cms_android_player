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
                PlayerLaunchHelper.launchPlayer(app, "crash.${throwable.javaClass.simpleName}")
                OrionRecoveryLogger.logRecoveryCompleted("crash.${throwable.javaClass.simpleName}")
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
