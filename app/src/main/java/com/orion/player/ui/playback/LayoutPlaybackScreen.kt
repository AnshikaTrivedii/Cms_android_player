package com.orion.player.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.orion.player.data.remote.LayoutInfo
import com.orion.player.data.remote.LayoutZoneInfo
import com.orion.player.data.remote.ZoneType
import com.orion.player.data.ticker.TickerDisplayConfig
import com.orion.player.ui.playback.ticker.TickerRenderer
import java.io.File

data class ZoneUiState(
    val zone: LayoutZoneInfo,
    val assets: List<com.orion.player.data.remote.AssetInfo>,
    val currentIndex: Int,
    val ticker: TickerDisplayConfig?
)

@Composable
fun LayoutPlaybackScreen(
    layout: LayoutInfo,
    zones: List<ZoneUiState>,
    localFiles: Map<String, File>,
    modifier: Modifier = Modifier,
    onAssetFailed: (String) -> Unit = {},
    onPlaybackStarted: (String) -> Unit = {},
    onUrlLoadSuccess: (String) -> Unit = {},
    onUrlLoadFailed: (String) -> Unit = {}
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenW = maxWidth
        val screenH = maxHeight

        zones
            .sortedBy { it.zone.zIndex }
            .forEach { zoneState ->
                val zone = zoneState.zone
                Box(
                    modifier = Modifier
                        .offset(
                            x = screenW * (zone.x / 100.0).toFloat(),
                            y = screenH * (zone.y / 100.0).toFloat()
                        )
                        .width(screenW * (zone.w / 100.0).toFloat())
                        .height(screenH * (zone.h / 100.0).toFloat())
                ) {
                    when (zone.type) {
                        ZoneType.PLAYLIST -> {
                            val asset = zoneState.assets.getOrNull(zoneState.currentIndex)
                            if (asset != null) {
                                AssetPlayback(
                                    asset = asset,
                                    localFile = localFiles[asset.id],
                                    modifier = Modifier.fillMaxSize(),
                                    onAssetFailed = onAssetFailed,
                                    onPlaybackStarted = onPlaybackStarted,
                                    onUrlLoadSuccess = onUrlLoadSuccess,
                                    onUrlLoadFailed = onUrlLoadFailed
                                )
                            } else {
                                UnavailableAssetPlaceholder()
                            }
                        }
                        ZoneType.TICKER -> {
                            zoneState.ticker?.let { ticker ->
                                TickerRenderer(
                                    config = ticker,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        ZoneType.IMAGE -> {
                            val asset = zoneState.assets.firstOrNull()
                            if (asset != null) {
                                AssetPlayback(
                                    asset = asset,
                                    localFile = localFiles[asset.id],
                                    modifier = Modifier.fillMaxSize(),
                                    onAssetFailed = onAssetFailed,
                                    onPlaybackStarted = onPlaybackStarted,
                                    onUrlLoadSuccess = onUrlLoadSuccess,
                                    onUrlLoadFailed = onUrlLoadFailed
                                )
                            } else {
                                UnavailableAssetPlaceholder()
                            }
                        }
                        ZoneType.HTML -> ZonePlaceholder("HTML Zone", Modifier.fillMaxSize())
                        ZoneType.CLOCK -> ZonePlaceholder("Clock Zone", Modifier.fillMaxSize())
                        else -> ZonePlaceholder("Zone: ${zone.type}", Modifier.fillMaxSize())
                    }
                }
            }
    }
}
