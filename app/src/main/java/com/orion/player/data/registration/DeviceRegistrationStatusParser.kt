package com.orion.player.data.registration

import android.util.Log
import com.google.gson.JsonParser
import retrofit2.HttpException

/**
 * Parses CMS device registration status from success payloads and 401 error bodies.
 * Network failures never produce a status — callers must ignore non-401 errors.
 */
object DeviceRegistrationStatusParser {
    private const val TAG = "OrionDeviceReg"

    fun fromSuccessField(raw: String?): DeviceRegistrationStatus? =
        DeviceRegistrationStatus.parse(raw)

    /**
     * Only HTTP 401 (authenticated rejection) can mean unregister/delete.
     * Temporary network / 5xx failures return null.
     */
    fun fromHttpException(error: HttpException): DeviceRegistrationStatus? {
        if (error.code() != 401) return null
        val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
        return fromUnauthorized(body, error.message())
    }

    fun fromUnauthorized(body: String?, fallbackMessage: String? = null): DeviceRegistrationStatus {
        val parsed = parseErrorBody(body)
        if (parsed != null) return parsed

        val message = body.orEmpty() + " " + (fallbackMessage.orEmpty())
        val lower = message.lowercase()
        return when {
            lower.contains("unregistered") ||
                (lower.contains("unpaired") && !lower.contains("invalid or unpaired")) ->
                DeviceRegistrationStatus.UNREGISTERED
            lower.contains("deleted") || lower.contains("missing device token") ->
                DeviceRegistrationStatus.DELETED
            // Legacy CMS hard-delete returned a generic unpaired message.
            lower.contains("invalid or unpaired") -> DeviceRegistrationStatus.DELETED
            else -> DeviceRegistrationStatus.DELETED
        }
    }

    private fun parseErrorBody(body: String?): DeviceRegistrationStatus? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return@runCatching null
            val obj = root.asJsonObject

            listOf("deviceStatus", "status", "registrationStatus")
                .asSequence()
                .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                .mapNotNull { DeviceRegistrationStatus.parse(it) }
                .firstOrNull()
                ?: obj.get("message")?.let { messageEl ->
                    when {
                        messageEl.isJsonObject ->
                            messageEl.asJsonObject.get("deviceStatus")
                                ?.takeIf { it.isJsonPrimitive }
                                ?.asString
                                ?.let { DeviceRegistrationStatus.parse(it) }
                        messageEl.isJsonPrimitive ->
                            DeviceRegistrationStatus.parse(messageEl.asString)
                        else -> null
                    }
                }
        }.onFailure { Log.w(TAG, "Failed to parse registration error body: ${it.message}") }
            .getOrNull()
    }
}
