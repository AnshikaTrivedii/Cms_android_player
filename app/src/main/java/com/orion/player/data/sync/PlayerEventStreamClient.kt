package com.orion.player.data.sync

import com.orion.player.BuildConfig
import com.orion.player.data.local.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to backend Server-Sent Events (SSE) for instant content-update notifications.
 * Falls back silently when the endpoint is unavailable (404 / connection errors).
 *
 * Expected backend endpoint: GET /api/player/events (text/event-stream)
 * Events: content.updated, playlist.updated, campaign.updated, ticker.updated, sync.required
 */
@Singleton
class PlayerEventStreamClient @Inject constructor(
    baseOkHttpClient: OkHttpClient,
    private val securePrefs: SecurePrefs
) {
    private val sseClient = baseOkHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _syncTriggers = MutableSharedFlow<SyncTrigger>(extraBufferCapacity = 8)
    val syncTriggers: SharedFlow<SyncTrigger> = _syncTriggers.asSharedFlow()

    private var streamJob: Job? = null
    private var reconnectAttempt = 0

    var isSupported: Boolean = true
        private set

    data class SyncTrigger(val reason: String, val revision: String? = null)

    fun start(scope: CoroutineScope) {
        streamJob?.cancel()
        streamJob = scope.launch(Dispatchers.IO) {
            while (isActive && isSupported) {
                val token = securePrefs.getBearerToken()
                if (token == null) {
                    delay(SyncConfig.SSE_RECONNECT_BASE_MS)
                    continue
                }

                try {
                    listenForEvents(token)
                    reconnectAttempt = 0
                } catch (e: Exception) {
                    e.printStackTrace()
                    val backoff = (SyncConfig.SSE_RECONNECT_BASE_MS * (1 shl reconnectAttempt.coerceAtMost(4)))
                        .coerceAtMost(SyncConfig.SSE_RECONNECT_MAX_MS)
                    reconnectAttempt++
                    delay(backoff)
                }
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun listenForEvents(bearerToken: String) {
        val url = BuildConfig.BASE_URL.trimEnd('/') + "/player/events"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", bearerToken)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val call = sseClient.newCall(request)
        val response = call.execute()

        if (response.code == 404) {
            isSupported = false
            response.close()
            return
        }

        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("SSE connection failed: HTTP ${response.code}")
        }

        try {
            response.body?.byteStream()?.bufferedReader()?.use { reader ->
                var eventType: String? = null
                val dataLines = mutableListOf<String>()

                while (true) {
                    val line = reader.readLine() ?: break

                    when {
                        line.startsWith("event:") -> {
                            eventType = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            dataLines.add(line.removePrefix("data:").trim())
                        }
                        line.isEmpty() -> {
                            if (dataLines.isNotEmpty()) {
                                dispatchEvent(eventType, dataLines.joinToString("\n"))
                            }
                            eventType = null
                            dataLines.clear()
                        }
                    }
                }
            }
        } finally {
            response.close()
        }
    }

    private fun dispatchEvent(eventType: String?, data: String) {
        val normalizedEvent = eventType?.lowercase().orEmpty()
        val isSyncEvent = normalizedEvent in SYNC_EVENTS ||
            data.contains("revision", ignoreCase = true) ||
            data.contains("syncRequired", ignoreCase = true)

        if (!isSyncEvent && normalizedEvent.isNotEmpty()) return

        val revision = extractRevision(data)
        val reason = normalizedEvent.ifBlank { "sync.required" }
        _syncTriggers.tryEmit(SyncTrigger(reason = reason, revision = revision))
    }

    private fun extractRevision(data: String): String? {
        val match = REVISION_REGEX.find(data) ?: return null
        return match.groupValues[1].trim('"', ' ', '\t')
    }

    companion object {
        private val SYNC_EVENTS = setOf(
            "content.updated",
            "playlist.updated",
            "campaign.updated",
            "ticker.updated",
            "sync.required",
            "content-updated",
            "playlist-updated"
        )

        private val REVISION_REGEX = Regex(""""revision"\s*:\s*"([^"]+)"""")
    }
}
