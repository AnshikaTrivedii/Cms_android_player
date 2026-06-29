package com.orion.player.ui.cache

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orion.player.BuildConfig
import com.orion.player.data.cache.CacheDebugInfo
import com.orion.player.data.cache.CacheDownloadLogger
import com.orion.player.data.cache.CacheDownloadStatus
import com.orion.player.data.cache.CachedAssetEntry

private val Accent = Color(0xFF6C63FF)
private val BgDark = Color(0xFF0D0D14)
private val CardBg = Color(0xFF1A1A24)
private val TextMuted = Color(0xFFB0B0C0)

@Composable
fun CacheDebugScreen(
    onBack: () -> Unit,
    viewModel: CacheDebugViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Cache Debug",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Accent)
            }
        }

        when (val state = uiState) {
            is CacheDebugUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
                }
            }
            is CacheDebugUiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, color = Color(0xFFFFB74D), fontSize = 14.sp)
                }
            }
            is CacheDebugUiState.Ready -> {
                CacheDebugContent(info = state.info)
            }
        }
    }
}

@Composable
private fun CacheDebugContent(info: CacheDebugInfo) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "Overview") {
                InfoRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("Network", if (info.isOnline) "Online" else "Offline")
                InfoRow("Download status", info.downloadProgress ?: "Idle")
                InfoRow("Last sync", info.lastSyncFormatted)
            }
        }

        item {
            SectionCard(title = "Current Playlist") {
                InfoRow("Name", info.playlistName ?: "—")
                InfoRow("Playlist ID", info.playlistId ?: "—")
                InfoRow("Version", info.playlistVersion?.toString() ?: "—")
                InfoRow("Playable assets", "${info.playableCount} / ${info.assets.size}")
                if (info.missingCount > 0) {
                    InfoRow("Missing", info.missingCount.toString(), valueColor = Color(0xFFFFB74D))
                }
            }
        }

        item {
            SectionCard(title = "Cache Storage") {
                InfoRow("Location", info.cacheStats.directoryPath, mono = true)
                InfoRow(
                    "Total size",
                    CacheDownloadLogger.formatBytes(info.cacheStats.totalSizeBytes)
                )
                InfoRow("Files on disk", info.cacheStats.fileCount.toString())
            }
        }

        item {
            Text(
                text = "Downloaded Assets (${info.assets.size})",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        if (info.assets.isEmpty()) {
            item {
                Text(
                    text = "No assets cached yet.",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            }
        } else {
            items(info.assets, key = { it.assetId }) { asset ->
                AssetCard(asset)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AssetCard(asset: CachedAssetEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = asset.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            StatusBadge(asset.status)
        }
        Spacer(Modifier.height(6.dp))
        Text("Type: ${asset.type}  •  Pos: ${asset.position}", color = TextMuted, fontSize = 12.sp)
        Text(
            text = "Size: ${CacheDownloadLogger.formatBytes(asset.fileSizeBytes)}",
            color = TextMuted,
            fontSize = 12.sp
        )
        asset.localPath?.let { path ->
            Text(
                text = path,
                color = TextMuted.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusBadge(status: CacheDownloadStatus) {
    val (label, color) = when (status) {
        CacheDownloadStatus.CACHED -> "Cached" to Color(0xFF4CAF50)
        CacheDownloadStatus.MISSING -> "Missing" to Color(0xFFFFB74D)
        CacheDownloadStatus.DOWNLOADING -> "Downloading" to Accent
        CacheDownloadStatus.FAILED -> "Failed" to Color(0xFFEF5350)
    }
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
