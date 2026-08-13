package com.orion.player.data.enterprise

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists crash stack traces for upload on next sync.
 */
@Singleton
class CrashLogStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Lazy and null-safe: the singleton can be built before the user unlocks the device,
    // when credential-protected storage is not yet accessible.
    private val crashFile: File? by lazy {
        runCatching { File(context.filesDir, "enterprise_logs/crash_latest.txt") }.getOrNull()
    }
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun recordCrash(throwable: Throwable) {
        val file = crashFile ?: return
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    appendLine("timestamp=${formatter.format(Date())}")
                    appendLine("type=${throwable.javaClass.name}")
                    appendLine("message=${throwable.message}")
                    appendLine("--- stack trace ---")
                    append(sw.toString())
                }
            )
        }
    }

    fun readPendingCrash(): String? {
        val file = crashFile ?: return null
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clearPendingCrash() {
        crashFile?.delete()
    }
}
