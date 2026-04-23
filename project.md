# Digital Signage Orion: Android Player & Secure Pairing Guide

This document provides a comprehensive, **production-ready** prompt for building the Android Player application, followed by the **exact API contracts** of the already-implemented NestJS backend. Hand this document to your AI assistant or Android developer — the backend is live and ready to accept requests.

---

## Part 1: Prompt for Building the Android Player

Copy the following text and provide it to your AI assistant or Android developer:

---
**Copy from here:**

**Objective:**
Build a robust, kiosk-mode Android application for a Digital Signage system ("Digital-Signage-Orion"). The app must run seamlessly on Android-based displays or TV boxes, fetching content from a NestJS backend and playing it in a continuous loop.

**Context:**
Our platform has a backend (NestJS) that manages `Organizations`, `Campaigns`, `Playlists`, and `Assets` (Images, Videos, HTML). The backend API is already fully implemented and documented in detail below. The Android player will consume these APIs.

**Core Requirements:**

1.  **Tech Stack:**
    *   Language: Kotlin
    *   UI: Jetpack Compose (preferred) or XML Layouts.
    *   Media Playback: **ExoPlayer** for videos (gapless loop), **Coil/Glide** for images, and **WebView** for HTML links.
    *   Networking: **Retrofit** with OkHttp.
    *   Asynchronous Operations: **Kotlin Coroutines** and **Flows**.
    *   Dependency Injection: **Hilt**.
    *   Local Database: **Room** (for offline PoP log queue).

2.  **Kiosk Mode / Lock Down:**
    *   The app must act as a device owner or utilize `startLockTask()` to pin the app to the screen.
    *   It should automatically launch on device boot (using `RECEIVE_BOOT_COMPLETED`).
    *   Hide system UI (immersive full-screen mode).
    *   Keep the screen on at all times (`FLAG_KEEP_SCREEN_ON`).

3.  **Secure Pairing & Provisioning Flow:**
    *   On the very first launch, generate a **UUID** (`hardwareId`) and persist it.
    *   Call `POST /api/player/init-pairing` with the `hardwareId`. The backend returns a 6-character alphanumeric `pairingCode`.
    *   Display the pairing code prominently on screen: _"Go to your Orion CMS dashboard, click **Add Device**, and enter code: **[CODE]**"_.
    *   **Background Polling:** Call `GET /api/player/pairing-status/{hardwareId}` every 5 seconds. When `isPaired` becomes `true`, the response includes a `deviceToken`.
    *   Securely store the `deviceToken` and `organizationId` using **EncryptedSharedPreferences**.
    *   Transition to the Main Playback screen.

4.  **Content Management & Offline-First Support:**
    *   **Fetch Playlist:** Call `GET /api/player/sync` (authenticated with device token). Returns the active playlist manifest with pre-signed download URLs for each asset.
    *   **Download & Cache Assets:** Do NOT stream continuously. Download all assets to local storage on first sync. Use Android's `DownloadManager` or OkHttp streams to save to internal cache.
    *   **Playback Loop:** Once the manifest and files are cached, play them seamlessly in order based on `durationSeconds` and `position`.
    *   **Periodic Re-sync:** Re-fetch the playlist manifest every 5 minutes to detect content updates. Download only new/changed assets.
    *   **Offline Mode:** If the internet disconnects, continue looping cached content indefinitely until connection is restored.

5.  **Proof of Play (PoP) & Device Health:**
    *   Queue playback logs locally using **Room Database**: which asset played, when, and whether it succeeded.
    *   Sync PoP logs to the backend via `POST /api/player/pop-logs` every 5 minutes.
    *   Send heartbeats via `POST /api/player/heartbeat` every 60 seconds with CPU%, RAM%, temperature, and the currently playing asset name.

**Please start by doing the following:**
1. Generate the foundational project structure (Gradle configuration, manifest permissions for Boot/Internet/Wake Lock).
2. Create the `PairingScreen` UI (where the pairing code is displayed) and the `PlaybackScreen` UI.
3. Write the Retrofit API interface matching the exact contracts documented below.
4. Write the pairing service logic (Coroutine loop for polling pairing status).

---
**End Copy**

---

## Part 2: Live API Reference (Already Implemented ✅)

> **Base URL:** `http://<YOUR_SERVER>:3001/api`
>
> All endpoints below are **live and tested**. The backend code lives in `apps/api/src/player/`.

---

### 2.1 Pairing Endpoints (Public — No Auth Required)

#### `POST /api/player/init-pairing`

Called by the Android app on first boot. Creates a draft Device record and returns a pairing code.

**Request:**
```json
{
  "hardwareId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response (200):**
```json
{
  "hardwareId": "550e8400-e29b-41d4-a716-446655440000",
  "isPaired": false,
  "pairingCode": "A3X9PZ"
}
```

**Idempotent:** If the same `hardwareId` calls again, it returns the existing code (won't create a duplicate). If already paired, returns `isPaired: true`.

---

#### `GET /api/player/pairing-status/:hardwareId`

Polled by the Android app every 5 seconds until pairing completes.

**Response (Unpaired):**
```json
{
  "isPaired": false,
  "deviceToken": null,
  "organizationId": null,
  "deviceName": null
}
```

**Response (Paired ✅):**
```json
{
  "isPaired": true,
  "deviceToken": "d0d7e397e6d1e943f8e11b3a14f9aec4c79ddb8ba97eaa321300c4581cd7840c",
  "organizationId": "cmo9wv2ml0001qvbyoga3bfp1",
  "deviceName": "Lobby Screen"
}
```

> **Important:** Once `isPaired` is `true`, store the `deviceToken` securely. All subsequent API calls use it for auth.

---

### 2.2 Authenticated Device Endpoints

> **Auth:** All endpoints below require the device token in the `Authorization` header:
> ```
> Authorization: Bearer <deviceToken>
> ```

---

#### `POST /api/player/heartbeat`

Send device health telemetry. Call every ~60 seconds.

**Request:**
```json
{
  "cpu": 35,
  "ram": 62,
  "temp": 42,
  "currentContent": "Summer Sale Campaign"
}
```
- `cpu`: integer 0–100 (CPU usage %)
- `ram`: integer 0–100 (memory usage %)
- `temp`: integer 0–120 (temperature in °C)
- `currentContent`: optional string — name of the currently playing asset

**Response (200):**
```json
{
  "status": "ok"
}
```

---

#### `GET /api/player/sync`

Fetch the active playlist assigned to this device, including pre-signed S3 download URLs (valid for 24 hours).

**Response (No playlist assigned):**
```json
{
  "playlist": null,
  "assets": []
}
```

**Response (With playlist):**
```json
{
  "playlist": {
    "id": "clxyz123",
    "name": "Lobby Playlist"
  },
  "assets": [
    {
      "id": "clxyz456",
      "name": "welcome-banner.jpg",
      "type": "IMAGE",
      "mimeType": "image/jpeg",
      "durationSeconds": 10,
      "position": 0,
      "downloadUrl": "https://s3.ap-south-1.amazonaws.com/orion-assets/...",
      "fileSize": 245670
    },
    {
      "id": "clxyz789",
      "name": "promo-video.mp4",
      "type": "VIDEO",
      "mimeType": "video/mp4",
      "durationSeconds": 30,
      "position": 1,
      "downloadUrl": "https://s3.ap-south-1.amazonaws.com/orion-assets/...",
      "fileSize": 15234567
    }
  ]
}
```

**Asset types:** `IMAGE`, `VIDEO`, `HTML`, `DOCUMENT`

**Playback logic:**
1. Sort assets by `position` (already sorted in response)
2. Play each asset for `durationSeconds`
3. For `IMAGE`: display using Glide/Coil for the specified duration
4. For `VIDEO`: play using ExoPlayer (may exceed `durationSeconds` — play to completion)
5. For `HTML`: render in a WebView for the specified duration
6. Loop back to position 0 when the last asset finishes

---

#### `POST /api/player/pop-logs`

Submit queued proof-of-play analytics. Call every ~5 minutes, or when the offline queue exceeds ~50 entries.

**Request:**
```json
{
  "logs": [
    {
      "content": "welcome-banner.jpg",
      "status": "VERIFIED",
      "timestamp": "2026-04-22T10:30:00.000Z"
    },
    {
      "content": "promo-video.mp4",
      "status": "VERIFIED",
      "timestamp": "2026-04-22T10:30:10.000Z"
    },
    {
      "content": "broken-asset.mp4",
      "status": "FAILED",
      "timestamp": "2026-04-22T10:30:40.000Z"
    }
  ]
}
```
- `status`: `"VERIFIED"` (played successfully) or `"FAILED"` (playback error)
- `timestamp`: ISO 8601 string — when the asset started playing

**Response (200):**
```json
{
  "received": 3
}
```

---

### 2.3 CMS-Side Pairing (For Reference — Already Built)

The CMS web dashboard at `/app/devices` has a **"Add Device"** button that opens a modal where the user enters the 6-digit pairing code and a display name. This calls:

```
POST /api/client-data/devices/pair
Authorization: Bearer <user-jwt>
x-organization-id: <org-id>

{ "pairingCode": "A3X9PZ", "name": "Lobby Screen" }
```

This assigns the device to the user's organization, generates the `deviceToken`, clears the pairing code, and sets `isPaired = true`.

---

## Part 3: Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PAIRING PHASE                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ANDROID PLAYER                    ORION API                        │
│  ─────────────                     ─────────                        │
│  1. Generate UUID (hardwareId)                                      │
│  2. POST /player/init-pairing ──►  Creates draft device             │
│     { hardwareId }              ◄── Returns { pairingCode: "A3X9PZ"}│
│  3. Display code on screen                                          │
│  4. Poll every 5s:                                                  │
│     GET /pairing-status/{id} ──►   Checks isPaired flag             │
│                               ◄── { isPaired: false }               │
│                                                                     │
│  CMS USER                                                           │
│  ────────                                                           │
│  5. Clicks "Add Device" on CMS                                      │
│  6. Enters "A3X9PZ" + "Lobby Screen"                                │
│  7. POST /client-data/devices/pair ► Assigns org, generates token   │
│                                                                     │
│  ANDROID PLAYER (next poll)                                         │
│  8. GET /pairing-status/{id} ──►                                    │
│                               ◄── { isPaired: true,                 │
│                                      deviceToken: "d0d7e3...",      │
│                                      organizationId: "cmo9..." }    │
│  9. Store token in EncryptedSharedPreferences                       │
│  10. Transition to Playback Screen                                  │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                       PLAYBACK PHASE                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Every 5 minutes:                                                   │
│  GET /player/sync ──────────────►  Returns playlist + asset URLs    │
│  Download new/changed assets       (pre-signed S3 URLs, 24h expiry) │
│  Play in loop (position order)                                      │
│                                                                     │
│  Every 60 seconds:                                                  │
│  POST /player/heartbeat ────────►  Updates device telemetry in CMS  │
│  { cpu, ram, temp, currentContent }                                 │
│                                                                     │
│  Every 5 minutes:                                                   │
│  POST /player/pop-logs ─────────►  Submits playback analytics       │
│  { logs: [...] }                   (flush Room DB queue)            │
│                                                                     │
│  OFFLINE MODE:                                                      │
│  If network unavailable → keep looping cached content               │
│  Queue heartbeats + PoP logs in Room DB                             │
│  Flush queues when connection restored                              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Part 4: Kotlin Retrofit Interface (Starter)

```kotlin
interface OrionPlayerApi {

    // ── Pairing (no auth) ──────────────────────────────────

    @POST("player/init-pairing")
    suspend fun initPairing(
        @Body body: InitPairingRequest
    ): InitPairingResponse

    @GET("player/pairing-status/{hardwareId}")
    suspend fun getPairingStatus(
        @Path("hardwareId") hardwareId: String
    ): PairingStatusResponse

    // ── Authenticated (device token) ───────────────────────

    @POST("player/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") token: String,
        @Body body: HeartbeatRequest
    ): HeartbeatResponse

    @GET("player/sync")
    suspend fun syncPlaylist(
        @Header("Authorization") token: String
    ): SyncResponse

    @POST("player/pop-logs")
    suspend fun submitPopLogs(
        @Header("Authorization") token: String,
        @Body body: PopLogsRequest
    ): PopLogsResponse
}

// ── Data Classes ───────────────────────────────────────────

data class InitPairingRequest(val hardwareId: String)
data class InitPairingResponse(
    val hardwareId: String,
    val isPaired: Boolean,
    val pairingCode: String?
)

data class PairingStatusResponse(
    val isPaired: Boolean,
    val deviceToken: String?,
    val organizationId: String?,
    val deviceName: String?
)

data class HeartbeatRequest(
    val cpu: Int,
    val ram: Int,
    val temp: Int,
    val currentContent: String? = null
)
data class HeartbeatResponse(val status: String)

data class SyncResponse(
    val playlist: PlaylistInfo?,
    val assets: List<AssetInfo>
)
data class PlaylistInfo(val id: String, val name: String)
data class AssetInfo(
    val id: String,
    val name: String,
    val type: String,        // IMAGE, VIDEO, HTML, DOCUMENT
    val mimeType: String,
    val durationSeconds: Int,
    val position: Int,
    val downloadUrl: String?,
    val fileSize: Int
)

data class PopLogEntry(
    val content: String,
    val status: String,      // "VERIFIED" or "FAILED"
    val timestamp: String    // ISO 8601
)
data class PopLogsRequest(val logs: List<PopLogEntry>)
data class PopLogsResponse(val received: Int)
```

> **Note:** Pass `"Bearer $deviceToken"` to the `token` parameter (include the `Bearer ` prefix).

---

## Part 5: Error Handling

All endpoints return standard HTTP error responses:

| Status | Meaning | Example |
|--------|---------|---------|
| `400` | Bad request / validation error | `{ "message": "hardwareId is required" }` |
| `401` | Invalid or missing device token | `{ "message": "Invalid or unpaired device token" }` |
| `404` | Device not found | `{ "message": "Unknown device. Call init-pairing first." }` |

The Android app should handle:
- **401 errors** on authenticated endpoints → return to the Pairing screen (token may have been revoked)
- **Network errors** → switch to offline mode, queue logs, retry with exponential backoff
- **404 on pairing-status** → re-call `init-pairing` to register the device again
