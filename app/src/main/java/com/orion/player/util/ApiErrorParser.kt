package com.orion.player.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException

object ApiErrorParser {

    fun HttpException.readableMessage(): String {
        val body = response()?.errorBody()?.string()
        if (!body.isNullOrBlank()) {
            try {
                val json = Gson().fromJson(body, JsonObject::class.java)
                val messages = json.get("message")
                when {
                    messages == null -> Unit
                    messages.isJsonArray -> {
                        val joined = messages.asJsonArray.joinToString("\n") { it.asString }
                        if (joined.isNotBlank()) return joined
                    }
                    messages.isJsonPrimitive -> return messages.asString
                }
            } catch (_: Exception) {
                return body
            }
        }
        return message()
    }
}
