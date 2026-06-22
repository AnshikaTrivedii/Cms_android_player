package com.orion.player.util

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.nio.charset.Charset

/**
 * Logs every HTTP request/response/exception for Orion API debugging.
 */
class ApiLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.currentTimeMillis()
        val requestBody = request.body?.let { body ->
            runCatching {
                Buffer().apply { body.writeTo(this) }.readString(Charset.forName("UTF-8"))
            }.getOrNull()
        }

        Log.d(TAG, "→ ${request.method} ${request.url}")
        if (!requestBody.isNullOrBlank()) {
            Log.d(TAG, "  Request body: $requestBody")
        }

        return try {
            val response = chain.proceed(request)
            val elapsed = System.currentTimeMillis() - started
            val responseBody = response.peekBody(MAX_LOG_BYTES).string()
            Log.d(
                TAG,
                "← ${response.code} ${request.method} ${request.url} (${elapsed}ms)\n  Response: $responseBody"
            )
            response
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - started
            NetworkDiagnostics.logException(
                "${request.method} ${request.url}",
                e,
                elapsed
            )
            throw e
        }
    }

    companion object {
        private const val TAG = "OrionApi"
        private const val MAX_LOG_BYTES = 64 * 1024L
    }
}
