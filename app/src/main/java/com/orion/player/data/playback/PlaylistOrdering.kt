package com.orion.player.data.playback

import com.orion.player.data.remote.AssetInfo

/**
 * Stable CMS playlist order by [AssetInfo.position].
 * Uses a stable sort so equal positions keep CMS encounter order — required when
 * the same asset appears multiple times in one playlist.
 */
fun List<AssetInfo>.inPlaylistOrder(): List<AssetInfo> =
    sortedBy { it.position }

fun List<AssetInfo>.orderedAssetIds(): List<String> = inPlaylistOrder().map { it.id }

/** Unique asset ids preserving first-seen order (for downloads / cache keys). */
fun List<AssetInfo>.uniqueAssetIdsInOrder(): List<String> {
    val seen = LinkedHashSet<String>()
    for (asset in inPlaylistOrder()) {
        if (asset.id.isNotBlank()) seen.add(asset.id)
    }
    return seen.toList()
}
