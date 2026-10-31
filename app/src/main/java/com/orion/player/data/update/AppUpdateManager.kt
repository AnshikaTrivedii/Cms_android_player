package com.orion.player.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.orion.player.BuildConfig
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.recovery.KioskController
import com.orion.player.data.recovery.PlayerHealthMonitor
import com.orion.player.data.remote.AppUpdateInfo
import com.orion.player.data.remote.AppUpdateStatusRequest
import com.orion.player.data.remote.OrionPlayerApi
import com.orion.player.receiver.AppInstallResultReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Update state survives process termination while PackageInstaller replaces this APK. */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: OrionPlayerApi,
    private val http: OkHttpClient,
    private val securePrefs: SecurePrefs,
    private val health: PlayerHealthMonitor
) {
    private val prefs = context.getSharedPreferences("orion_app_update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val processStartedAt = System.currentTimeMillis()
    private val directory = File(context.filesDir, "app_updates").apply { mkdirs() }

    init {
        if (prefs.getString("state", null) == "INSTALLING" &&
            installedVersion() == prefs.getInt("versionCode", -1)) {
            val starts = prefs.getInt("postInstallStarts", 0) + 1
            prefs.edit().putInt("postInstallStarts", starts).commit()
            if (starts >= 3) scope.launch { block("Updated app restarted repeatedly before healthy playback") }
        }
        if (prefs.getString("state", null) == "INSTALLING" &&
            installedVersion() < prefs.getInt("versionCode", -1)) {
            scope.launch {
                val startedAt = prefs.getLong("installStartedAt", System.currentTimeMillis())
                delay((startedAt + INSTALL_TIMEOUT_MS - System.currentTimeMillis()).coerceAtLeast(0))
                if (prefs.getString("state", null) == "INSTALLING" &&
                    installedVersion() < prefs.getInt("versionCode", -1)) {
                    block("PackageInstaller did not complete the update")
                }
            }
        }
    }

    fun observe(update: AppUpdateInfo?) {
        if (update == null || update.versionCode <= installedVersion()) return
        if (prefs.getString("releaseId", null) == update.releaseId &&
            prefs.getString("state", null) == "BLOCKED") return
        if (!KioskController.isDeviceOwner(context)) {
            scope.launch {
                save(update, "BLOCKED", 0)
                report("BLOCKED", error = "Device owner provisioning is required for silent updates")
            }
            return
        }
        scope.launch {
            mutex.withLock {
                if (prefs.getString("releaseId", null) == update.releaseId &&
                    prefs.getString("state", null) == "VERIFIED" &&
                    !File(directory, "${update.releaseId}.apk").isFile) {
                    prefs.edit().putString("state", "FAILED").commit()
                }
                if (!UpdatePolicy.shouldDownload(
                        update.versionCode, installedVersion(),
                        prefs.getString("releaseId", null) == update.releaseId,
                        prefs.getString("state", null), prefs.getLong("retryAfter", 0),
                        System.currentTimeMillis()
                    )) return@withLock
                save(update, "DOWNLOADING", 0)
                report("DOWNLOADING")
                downloadAndVerify(update)
            }
        }
    }

    private suspend fun downloadAndVerify(initial: AppUpdateInfo) {
        var update = initial
        val part = File(directory, "${initial.releaseId}.part")
        val apk = File(directory, "${initial.releaseId}.apk")
        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                if (!update.downloadUrl.startsWith("https://") && !BuildConfig.DEBUG) {
                    throw SecurityException("Update download URL must use HTTPS")
                }
                if (StatFs(directory.path).availableBytes < update.sizeBytes * 2) {
                    block("Insufficient free storage for APK installation")
                    return
                }
                val request = Request.Builder().url(update.downloadUrl).get().build()
                http.newBuilder().callTimeout(30, TimeUnit.MINUTES).build()
                    .newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("APK download HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Empty APK response")
                    part.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var lastProgress = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > update.sizeBytes) throw SecurityException("APK exceeds announced size")
                                output.write(buffer, 0, read)
                                val progress = (total * 100 / update.sizeBytes).toInt()
                                if (progress / 10 != lastProgress / 10) {
                                    lastProgress = progress
                                    prefs.edit().putInt("progress", progress).apply()
                                    report("DOWNLOADING", progress)
                                }
                            }
                        }
                    }
                }
                verify(part, update)
                if (!part.renameTo(apk)) throw IllegalStateException("Could not stage verified APK")
                prefs.edit().putString("state", "VERIFIED").putInt("progress", 100).commit()
                report("VERIFIED", 100)
                Log.i(TAG, "Verified update ${update.versionCode}; waiting for asset boundary")
                return
            } catch (error: SecurityException) {
                part.delete()
                block(error.message ?: "APK verification failed")
                return
            } catch (error: Exception) {
                part.delete()
                Log.w(TAG, "Update download attempt $attempt failed", error)
                if (attempt < 3) {
                    delay(attempt * 5_000L)
                    val refreshed = runCatching {
                        api.getAppUpdate(token()).appUpdate
                    }.getOrNull()
                    if (refreshed?.releaseId != initial.releaseId) {
                        prefs.edit().putString("state", "FAILED").commit()
                        report("FAILED", error = "Release is no longer active")
                        return
                    }
                    update = refreshed
                }
            }
        }
        prefs.edit().putString("state", "FAILED").putLong("retryAfter",
            System.currentTimeMillis() + 15 * 60_000L).putInt("retryCount",
            prefs.getInt("retryCount", 0) + 1).commit()
        report("FAILED", error = "APK download failed after three attempts")
    }

    /** Invoked only after the current playback slot has ended. */
    fun installAtBoundary() {
        if (prefs.getString("state", null) != "VERIFIED") return
        if (!mutex.tryLock()) return
        scope.launch {
          try {
            if (prefs.getString("state", null) != "VERIFIED") return@launch
            val releaseId = prefs.getString("releaseId", null) ?: return@launch
            val refreshed = runCatching { api.getAppUpdate(token()) }.getOrNull()
                ?: return@launch // Stay ready if temporarily offline.
            val candidate = refreshed.appUpdate
            if (candidate == null) {
                prefs.edit().putString("state", "FAILED").commit()
                File(directory, "$releaseId.apk").delete()
                report("FAILED", error = "Release was deactivated")
                return@launch
            }
            if (candidate.releaseId != releaseId) {
                prefs.edit().putString("state", "FAILED").commit()
                report("FAILED", error = "Release was superseded")
                return@launch
            }
            if (!KioskController.isDeviceOwner(context)) {
                block("Device owner privilege was lost before installation")
                return@launch
            }
            val apk = File(directory, "$releaseId.apk")
            try {
                verify(apk, candidate)
                prefs.edit().putString("state", "INSTALLING").putInt("postInstallStarts", 0)
                    .putLong("installStartedAt", System.currentTimeMillis()).commit()
                report("INSTALLING", 100)
                commit(apk)
            } catch (error: Exception) {
                block(error.message ?: "Package installation failed")
            }
          } finally {
            mutex.unlock()
          }
        }
    }

    private fun verify(apk: File, update: AppUpdateInfo) {
        if (!apk.isFile || apk.length() != update.sizeBytes) throw SecurityException("APK size mismatch")
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(apk).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(update.sha256, ignoreCase = true)) throw SecurityException("APK SHA-256 mismatch")
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw SecurityException("Android could not parse the APK")
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName ||
            versionCode(archive) != update.versionCode.toLong() ||
            versionCode(archive) <= versionCode(installed)) {
            throw SecurityException("APK package or version is invalid")
        }
        if (signerDigests(archive) != signerDigests(installed) || signerDigests(archive).isEmpty()) {
            throw SecurityException("APK signing certificate differs from installed player")
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else info.signatures?.toList().orEmpty()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("player.apk", 0, apk.length()).use { output ->
                    FileInputStream(apk).use { it.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, AppInstallResultReceiver::class.java).apply {
                    action = AppInstallResultReceiver.ACTION_INSTALL_RESULT
                    putExtra("releaseId", prefs.getString("releaseId", null))
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val sender = PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
                session.commit(sender)
            }
        } catch (error: Exception) {
            installer.abandonSession(sessionId)
            throw error
        }
    }

    fun onInstallResult(releaseId: String?, status: Int, message: String?) {
        if (releaseId != prefs.getString("releaseId", null) ||
            prefs.getString("state", null) != "INSTALLING") return
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val reason = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION)
            "Android requested user confirmation; silent install unavailable"
        else "PackageInstaller rejected update: ${message.orEmpty()}"
        prefs.edit().putString("state", "BLOCKED").putString("error", reason).commit()
        scope.launch {
            report("BLOCKED", error = reason)
        }
    }

    fun onHeartbeatSuccess() {
        if (prefs.getString("state", null) == "BLOCKED") {
            // Capture the terminal report before observe() can switch preferences to
            // a newly offered release on another coroutine.
            val blockedReleaseId = prefs.getString("releaseId", null)
            val blockedProgress = prefs.getInt("progress", 0)
            val blockedError = prefs.getString("error", null)
            val blockedRetryCount = prefs.getInt("retryCount", 0)
            scope.launch {
                report(
                    state = "BLOCKED",
                    progress = blockedProgress,
                    error = blockedError,
                    releaseId = blockedReleaseId,
                    retryCount = blockedRetryCount
                )
            }
            return
        }
        if (!UpdatePolicy.isHealthy(
                prefs.getString("state", null), installedVersion(), prefs.getInt("versionCode", -1),
                health.isActivityAlive, health.isPlaybackActive,
                health.currentSlotStartMs, processStartedAt
            )) {
            if (prefs.getString("state", null) == "INSTALLING" &&
                System.currentTimeMillis() - prefs.getLong("installStartedAt", System.currentTimeMillis()) >
                5 * 60_000L) {
                scope.launch { block("Updated player did not resume healthy playback within five minutes") }
            }
            return
        }
        scope.launch {
            mutex.withLock {
                if (prefs.getString("state", null) == "INSTALLING") {
                    if (report("HEALTHY", 100, installedVersionCode = installedVersion())) {
                        prefs.edit().putString("state", "HEALTHY").commit()
                    }
                }
            }
        }
    }

    fun onPackageReplaced() {
        Log.i(TAG, "Package replaced; waiting for visible playback and heartbeat")
    }

    private fun save(update: AppUpdateInfo, state: String, progress: Int) {
        val differentRelease = prefs.getString("releaseId", null) != update.releaseId
        prefs.edit().putString("releaseId", update.releaseId).putInt("versionCode", update.versionCode)
            .putLong("sizeBytes", update.sizeBytes).putString("sha256", update.sha256)
            .putString("state", state).putInt("progress", progress).putLong("retryAfter", 0)
            .apply {
                if (differentRelease) remove("error").putInt("retryCount", 0).putInt("postInstallStarts", 0)
            }.commit()
    }

    private suspend fun block(reason: String) {
        prefs.edit().putString("state", "BLOCKED").putString("error", reason).commit()
        report("BLOCKED", error = reason)
        Log.e(TAG, reason)
    }

    private suspend fun report(state: String, progress: Int = prefs.getInt("progress", 0),
                               error: String? = null, installedVersionCode: Int? = null,
                               releaseId: String? = prefs.getString("releaseId", null),
                               retryCount: Int = prefs.getInt("retryCount", 0)): Boolean {
        val id = releaseId ?: return false
        return runCatching {
            api.reportAppUpdate(token(), AppUpdateStatusRequest(id, state, progress, error,
                retryCount, installedVersionCode))
            true
        }.getOrElse {
            Log.w(TAG, "Could not report update state $state", it)
            false
        }
    }

    private fun token(): String = "Bearer ${securePrefs.deviceToken ?: error("Player not paired")}"

    private fun installedVersion(): Int = BuildConfig.VERSION_CODE

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    companion object {
        private const val TAG = "OrionAppUpdate"
        private const val INSTALL_TIMEOUT_MS = 10 * 60_000L
    }
}
