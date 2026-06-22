package com.orion.player.util

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Retries transient network failures (timeouts, cold starts).
 */
suspend fun <T> retryOnNetworkFailure(
    endpoint: String,
    maxAttempts: Int = 3,
    initialDelayMs: Long = 2_000L,
    block: suspend () -> T
): T {
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastError = e
            if (!isRetryable(e) || attempt == maxAttempts - 1) {
                NetworkDiagnostics.logException(endpoint, e)
                throw e
            }
            val delayMs = min(initialDelayMs * (1 shl attempt), 15_000L)
            Log.w(
                "OrionNetwork",
                "Retry ${attempt + 1}/$maxAttempts for $endpoint in ${delayMs}ms: ${NetworkDiagnostics.describe(e)}"
            )
            delay(delayMs)
        }
    }
    throw lastError ?: IllegalStateException("retryOnNetworkFailure exhausted")
}

private fun isRetryable(e: Throwable): Boolean {
    val root = generateSequence(e) { it.cause }.last()
    return root is java.io.IOException && root !is retrofit2.HttpException
}
