package com.orion.player.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Retrofit interface matching the Orion Player API contracts.
 * Base URL is configured in the Hilt AppModule.
 */
interface OrionPlayerApi {

    // ── Pairing (no auth) ──────────────────────────────────

    @POST("player/init-pairing")
    suspend fun initPairing(
        @Body body: InitPairingRequest
    ): InitPairingResponse

    @GET
    suspend fun getPairingStatusByUrl(@Url url: String): PairingStatusResponse

    // ── Authenticated (device token) ───────────────────────

    @POST("player/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") token: String,
        @Body body: HeartbeatRequest
    ): HeartbeatResponse

    @GET("player/sync")
    suspend fun syncPlaylist(
        @Header("Authorization") token: String,
        @Query("playlistVersion") playlistVersion: Int? = null,
        @Query("layoutVersion") layoutVersion: Int? = null,
        @Query("knownAssetIds") knownAssetIds: String? = null,
        @Query("assetVersions") assetVersions: String? = null
    ): SyncResponse

    @GET("player/sync-revision")
    suspend fun getSyncRevision(
        @Header("Authorization") token: String
    ): SyncRevisionResponse

    @POST("player/pop-logs")
    suspend fun submitPopLogs(
        @Header("Authorization") token: String,
        @Body body: PopLogsRequest
    ): PopLogsResponse
}
