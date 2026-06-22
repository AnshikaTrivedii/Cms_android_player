package com.orion.player.util

import android.util.Log
import com.orion.player.BuildConfig
import com.orion.player.util.ApiErrorParser.readableMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object NetworkDiagnostics {

    private const val TAG = "OrionNetwork"

    fun logStartupConfig() {
        Log.i(TAG, "BASE_URL=${BuildConfig.BASE_URL}")
        Log.i(TAG, "VERSION=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    /**
     * Wakes Render cold-start instances before pairing. Returns true if server responded.
     */
    fun warmUpServer(client: OkHttpClient): WarmUpResult {
        val healthUrl = BuildConfig.BASE_URL.trimEnd('/') + "/health"
        val started = System.currentTimeMillis()
        Log.d(TAG, "warmUp GET $healthUrl")

        return try {
            val request = Request.Builder().url(healthUrl).get().build()
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - started
            val body = response.body?.string().orEmpty()
            Log.d(TAG, "warmUp ← ${response.code} in ${elapsed}ms body=$body")
            WarmUpResult(
                success = response.isSuccessful,
                statusCode = response.code,
                elapsedMs = elapsed,
                body = body
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - started
            logException("warmUp GET $healthUrl", e, elapsed)
            WarmUpResult(success = false, elapsedMs = elapsed, error = describe(e))
        }
    }

    fun logException(endpoint: String, e: Throwable, elapsedMs: Long? = null) {
        val timing = elapsedMs?.let { " after ${it}ms" }.orEmpty()
        Log.e(TAG, "FAILED $endpoint$timing: ${describe(e)}", e)
    }

    fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val type = root.javaClass.simpleName
        val detail = when (root) {
            is SocketTimeoutException -> "Server did not respond in time (Render cold start?)"
            is UnknownHostException -> "DNS failed — cannot resolve API host"
            is SSLException -> "SSL/TLS handshake failed"
            is HttpException -> "HTTP ${root.code()}: ${root.message()}"
            is IOException -> root.message ?: "Network I/O error"
            else -> root.message ?: e.message ?: "Unknown error"
        }
        return "$type: $detail"
    }

    fun userMessage(endpoint: String, e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return when (root) {
            is SocketTimeoutException ->
                "Server timeout on $endpoint.\nThe API may be waking up (Render cold start). Tap Retry and wait up to 90 seconds."
            is UnknownHostException ->
                "Cannot reach API server.\nCheck internet connection and DNS.\n${BuildConfig.BASE_URL}"
            is SSLException ->
                "Secure connection failed on $endpoint.\nCheck device date/time and network."
            is HttpException -> {
                val body = runCatching { root.readableMessage() }.getOrDefault(root.message())
                "$endpoint failed:\n$body"
            }
            else -> "$endpoint failed:\n${describe(e)}"
        }
    }

    data class WarmUpResult(
        val success: Boolean,
        val statusCode: Int = 0,
        val elapsedMs: Long = 0,
        val body: String = "",
        val error: String? = null
    )
}
