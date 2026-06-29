package com.orion.player.data.remote

import com.google.gson.annotations.SerializedName
import com.orion.player.data.ticker.TickerInfo
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.data.ticker.toDisplayConfig

object ZoneType {
    const val PLAYLIST = "PLAYLIST"
    const val TICKER = "TICKER"
    const val IMAGE = "IMAGE"
    const val HTML = "HTML"
    const val CLOCK = "CLOCK"
}

data class LayoutInfo(
    @SerializedName("id") private val idRaw: String? = null,
    @SerializedName("name") private val nameRaw: String? = null,
    @SerializedName("resolution") private val resolutionRaw: String? = null,
    @SerializedName("zones") private val zonesRaw: List<LayoutZoneInfo>? = null
) {
    val id: String get() = idRaw.orEmpty()
    val name: String get() = nameRaw.orEmpty()
    val resolution: String get() = resolutionRaw.orEmpty()
    val zones: List<LayoutZoneInfo> get() = zonesRaw.orEmpty()
}

data class LayoutZoneInfo(
    @SerializedName("id") private val idRaw: String? = null,
    @SerializedName("name") private val nameRaw: String? = null,
    @SerializedName("type") private val typeRaw: String? = null,
    @SerializedName("x") private val xRaw: Double? = null,
    @SerializedName("y") private val yRaw: Double? = null,
    @SerializedName("w") private val wRaw: Double? = null,
    @SerializedName("h") private val hRaw: Double? = null,
    @SerializedName("zIndex") private val zIndexRaw: Int? = null,
    @SerializedName("playlistId") val playlistId: String? = null,
    @SerializedName("playlistVersion") val playlistVersion: Int? = null,
    @SerializedName("playlistName") val playlistName: String? = null,
    @SerializedName("assets") val assets: List<AssetInfo>? = null,
    @SerializedName("assetId") val assetId: String? = null,
    @SerializedName("asset") val asset: AssetInfo? = null,
    @SerializedName("ticker") val ticker: TickerInfo? = null
) {
    val id: String get() = idRaw.orEmpty()
    val name: String get() = nameRaw.orEmpty()
    val type: String get() = typeRaw.orEmpty().uppercase()
    val x: Double get() = xRaw ?: 0.0
    val y: Double get() = yRaw ?: 0.0
    val w: Double get() = wRaw ?: 100.0
    val h: Double get() = hRaw ?: 100.0
    val zIndex: Int get() = zIndexRaw ?: 0

    fun resolvedAssets(): List<AssetInfo> = assets.orEmpty().filter { it.id.isNotBlank() }

    fun resolvedSingleAsset(): AssetInfo? = asset?.takeIf { it.id.isNotBlank() }
}

data class ZoneSnapshot(
    val zone: LayoutZoneInfo,
    val assets: List<AssetInfo>,
    val currentIndex: Int,
    val ticker: TickerDisplayConfig?
)

fun LayoutInfo.toZoneSnapshots(allAssets: List<AssetInfo> = emptyList()): List<ZoneSnapshot> =
    LayoutAssetResolver.hydrateZoneSnapshots(this, allAssets)

fun LayoutInfo.collectLayoutAssets(manifestAssets: List<AssetInfo> = emptyList()): List<AssetInfo> =
    LayoutAssetResolver.collectLayoutAssets(this, manifestAssets)
