package com.orion.player.data.playback

import com.orion.player.data.remote.AssetInfo

/** Stable CMS playlist order: primary sort by [AssetInfo.position], tie-break by asset id. */
fun List<AssetInfo>.inPlaylistOrder(): List<AssetInfo> =
    sortedWith(compareBy({ it.position }, { it.id }))

fun List<AssetInfo>.orderedAssetIds(): List<String> = inPlaylistOrder().map { it.id }
