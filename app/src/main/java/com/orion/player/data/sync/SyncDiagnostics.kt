package com.orion.player.data.sync

import android.util.Log
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType.isPlayable
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.data.remote.LayoutInfo
import com.orion.player.data.remote.ZoneType
import com.orion.player.data.remote.SyncResponse
import com.orion.player.data.repository.ContentRepository
import com.orion.player.data.repository.PlaylistCacheRepository
import java.io.File

/**
 * Structured diagnostics for Device → Sync → Download → Cache → Playback tracing.
 * Filter logcat: adb logcat -s OrionSync
 */
object SyncDiagnostics {
    private const val TAG = "OrionSync"

    fun logStartupDevice(securePrefs: SecurePrefs) {
        Log.i(
            TAG,
            "STEP 1 startup: hardwareId=${securePrefs.getOrCreateHardwareId()} " +
                "deviceName=${securePrefs.deviceName.orEmpty()} " +
                "orgId=${securePrefs.organizationId.orEmpty()} " +
                "isPaired=${securePrefs.isPaired} " +
                "hasToken=${!securePrefs.deviceToken.isNullOrBlank()}"
        )
    }

    fun logCacheLoad(
        snapshot: PlaybackSnapshot?,
        roomAssetCount: Int,
        roomPlaylistPresent: Boolean,
        diskFileCount: Int
    ) {
        val playable = snapshot?.assets?.count { it.isPlayable(snapshot.localFiles) } ?: 0
        Log.i(
            TAG,
            "STEP 4 cache load: roomPlaylist=$roomPlaylistPresent roomAssets=$roomAssetCount " +
                "diskFiles=$diskFileCount snapshot=${snapshot != null} " +
                "playableAssets=$playable"
        )
        snapshot?.localFiles?.forEach { (id, file) ->
            Log.i(TAG, "STEP 4 cached file: assetId=$id path=${file.absolutePath} bytes=${file.length()}")
        }
        if (snapshot == null) {
            Log.w(TAG, "STEP 4 cache load: no playable snapshot (empty Room rows or missing disk files)")
        }
    }

    fun logSyncResponse(securePrefs: SecurePrefs, response: SyncResponse) {
        val playlist = response.playlist
        val layout = response.layout
        Log.i(
            TAG,
            "STEP 2 sync response: deviceId=${securePrefs.getOrCreateHardwareId()} " +
                "deviceName=${securePrefs.deviceName.orEmpty()} " +
                "playlistId=${playlist?.id.orEmpty()} " +
                "playlistName=${playlist?.name.orEmpty()} " +
                "layoutId=${layout?.id.orEmpty()} layoutZones=${layout?.zones?.size ?: 0} " +
                "unchanged=${response.unchanged} " +
                "assetCount=${response.resolvedAssets().size}"
        )
        response.resolvedAssets().forEach { asset ->
            Log.i(
                TAG,
                "STEP 2 asset: id=${asset.id} name=${asset.name} type=${asset.type} " +
                    "position=${asset.position} hasDownloadUrl=${!asset.downloadUrl.isNullOrBlank()} " +
                    "hasUrl=${!asset.url.isNullOrBlank()} fileSize=${asset.fileSize}"
            )
        }
    }

    fun logLayoutZones(layout: LayoutInfo, mergedAssets: List<AssetInfo>) {
        Log.i(TAG, "STEP 2 layout: id=${layout.id} zones=${layout.zones.size} mergedAssets=${mergedAssets.size}")
        layout.zones.forEach { zone ->
            val resolvedCount = when (zone.type) {
                ZoneType.PLAYLIST, ZoneType.IMAGE -> zone.resolvedAssets().size +
                    (if (zone.resolvedSingleAsset() != null) 1 else 0)
                else -> 0
            }
            Log.i(
                TAG,
                "STEP 2 zone: id=${zone.id} type=${zone.type} assetId=${zone.assetId.orEmpty()} " +
                    "embeddedAssets=${zone.resolvedAssets().size} hasTicker=${zone.ticker != null} " +
                    "resolvedCount=$resolvedCount"
            )
        }
        mergedAssets.forEach { asset ->
            Log.i(
                TAG,
                "STEP 2 merged asset: id=${asset.id} name=${asset.name} " +
                    "hasDownloadUrl=${!asset.downloadUrl.isNullOrBlank()}"
            )
        }
    }

    fun logSyncNoContent() {
        Log.w(TAG, "STEP 2 sync response: playlist=null OR assets=[] → NoContent screen")
    }

    fun logDownloadStarted(asset: AssetInfo) {
        Log.i(TAG, "STEP 3 download started: name=${asset.name} type=${asset.normalizedType()} id=${asset.id}")
    }

    fun logDownloadSuccess(asset: AssetInfo, file: File) {
        Log.i(
            TAG,
            "STEP 3 download success: name=${asset.name} path=${file.absolutePath} bytes=${file.length()}"
        )
    }

    fun logDownloadFailed(asset: AssetInfo, reason: String) {
        Log.e(TAG, "STEP 3 download failed: name=${asset.name} type=${asset.type} reason=$reason")
    }

    fun logPlayabilityCheck(assets: List<AssetInfo>, localFiles: Map<String, File>) {
        val playable = assets.count { it.isPlayable(localFiles) }
        Log.i(TAG, "STEP 5 playability: total=${assets.size} playable=$playable")
        assets.forEach { asset ->
            val file = localFiles[asset.id]
            val ok = asset.isPlayable(localFiles)
            Log.i(
                TAG,
                "STEP 5 asset ${asset.name}: playable=$ok " +
                    "file=${file?.absolutePath ?: "missing"} " +
                    "exists=${file?.exists() == true} bytes=${file?.length() ?: 0}"
            )
        }
    }

    fun logPlaybackStart(playlistName: String, assetName: String, assetIndex: Int, total: Int) {
        Log.i(
            TAG,
            "STEP 5 playback started: playlist=$playlistName asset=$assetName index=$assetIndex/$total"
        )
    }

    fun logWaitingScreen(reason: String, trigger: String) {
        Log.e(TAG, "WAITING SCREEN: trigger=$trigger reason=$reason")
    }

    fun logSyncFailed(message: String, cause: Throwable? = null) {
        Log.e(TAG, "Sync failed: $message", cause)
    }

    fun logOutcome(outcome: String, detail: String = "") {
        Log.i(TAG, "Sync outcome: $outcome${if (detail.isNotBlank()) " — $detail" else ""}")
    }
}
