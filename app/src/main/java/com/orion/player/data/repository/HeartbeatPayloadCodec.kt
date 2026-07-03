package com.orion.player.data.repository

import com.google.gson.Gson
import com.orion.player.data.remote.GsonConfig
import com.orion.player.data.remote.HeartbeatRequest

internal object HeartbeatPayloadCodec {
    private val gson: Gson = GsonConfig.create()

    fun encode(request: HeartbeatRequest): String = gson.toJson(request)

    fun decode(json: String): HeartbeatRequest? =
        runCatching { gson.fromJson(json, HeartbeatRequest::class.java) }.getOrNull()
}
