package com.orion.player.data.remote

import com.orion.player.data.ticker.toDisplayConfig

/**
 * Resolves zone asset references against the flat top-level [assets] manifest from /sync.
 * Zone payloads may only include assetId or partial AssetInfo — the manifest has download URLs.
 */
object LayoutAssetResolver {

    /**
     * Union of top-level manifest assets and every asset referenced by layout zones.
     * Merges duplicates so zone stubs gain downloadUrl/url from the manifest.
     */
    fun collectLayoutAssets(layout: LayoutInfo, manifestAssets: List<AssetInfo>): List<AssetInfo> {
        val byId = linkedMapOf<String, AssetInfo>()
        manifestAssets.forEach { asset ->
            if (asset.id.isBlank()) return@forEach
            byId[asset.id] = byId[asset.id]?.mergeWith(asset) ?: asset
        }
        layout.zones.forEach { zone ->
            zone.resolvedAssets().forEach { embedded ->
                if (embedded.id.isBlank()) return@forEach
                byId[embedded.id] = byId[embedded.id]?.mergeWith(embedded) ?: embedded
            }
            zone.resolvedSingleAsset()?.let { embedded ->
                if (embedded.id.isBlank()) return@let
                byId[embedded.id] = byId[embedded.id]?.mergeWith(embedded) ?: embedded
            }
            zone.assetId?.takeIf { it.isNotBlank() }?.let { id ->
                if (id !in byId) {
                    byId[id] = AssetInfo(idRaw = id)
                }
            }
        }
        return byId.values.sortedBy { it.position }
    }

    fun hydrateZoneSnapshots(layout: LayoutInfo, allAssets: List<AssetInfo>): List<ZoneSnapshot> {
        val byId = allAssets.associateBy { it.id }
        return layout.zones.map { zone ->
            when (zone.type) {
                ZoneType.PLAYLIST -> {
                    val assets = resolvePlaylistAssets(zone, byId)
                    ZoneSnapshot(
                        zone = zone,
                        assets = assets,
                        currentIndex = 0,
                        ticker = null
                    )
                }
                ZoneType.TICKER -> ZoneSnapshot(
                    zone = zone,
                    assets = emptyList(),
                    currentIndex = 0,
                    ticker = zone.ticker?.toDisplayConfig()
                )
                ZoneType.IMAGE -> ZoneSnapshot(
                    zone = zone,
                    assets = listOfNotNull(resolveImageAsset(zone, byId)),
                    currentIndex = 0,
                    ticker = null
                )
                else -> ZoneSnapshot(
                    zone = zone,
                    assets = emptyList(),
                    currentIndex = 0,
                    ticker = null
                )
            }
        }
    }

    private fun resolvePlaylistAssets(
        zone: LayoutZoneInfo,
        byId: Map<String, AssetInfo>
    ): List<AssetInfo> {
        val embedded = zone.resolvedAssets()
        if (embedded.isNotEmpty()) {
            return embedded
                .map { asset -> (byId[asset.id] ?: asset).mergeWith(asset) }
                .sortedBy { it.position }
        }
        return emptyList()
    }

    private fun resolveImageAsset(
        zone: LayoutZoneInfo,
        byId: Map<String, AssetInfo>
    ): AssetInfo? {
        val embedded = zone.resolvedSingleAsset()
        val id = embedded?.id?.takeIf { it.isNotBlank() } ?: zone.assetId?.takeIf { it.isNotBlank() }
        if (id != null) {
            val manifest = byId[id]
            return when {
                manifest != null && embedded != null -> manifest.mergeWith(embedded)
                manifest != null -> manifest
                else -> embedded
            }
        }
        return embedded
    }
}
