package com.orion.player.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.data.remote.AssetType.remoteSourceUrl
import com.orion.player.ui.playback.player.DocumentPlayer
import com.orion.player.ui.playback.player.ImagePlayer
import com.orion.player.ui.playback.player.UrlPlayer
import com.orion.player.ui.playback.player.VideoPlayer
import java.io.File

/**
 * Reusable asset renderer for full-screen and zoned playback.
 * Supported types: IMAGE, VIDEO, URL, DOCUMENT.
 */
@Composable
fun AssetPlayback(
    asset: AssetInfo,
    localFile: File?,
    playbackSessionId: String = "",
    videoStopToken: Long = 0L,
    modifier: Modifier = Modifier,
    onAssetFailed: (String) -> Unit = {},
    onPlaybackStarted: (String) -> Unit = {},
    onVideoEnded: (String) -> Unit = {},
    onUrlLoadSuccess: (String) -> Unit = {},
    onUrlLoadFailed: (String) -> Unit = {},
    onVideoRendererPulse: () -> Unit = {}
) {
    when (asset.normalizedType()) {
        AssetType.IMAGE -> {
            val remoteUrl = asset.remoteSourceUrl()
            when {
                localFile != null -> ImagePlayer(
                    file = localFile,
                    onReady = { onPlaybackStarted(asset.name) },
                    onFailed = { onAssetFailed(asset.name) },
                    modifier = modifier
                )
                !remoteUrl.isNullOrBlank() -> ImagePlayer(
                    url = remoteUrl,
                    onReady = { onPlaybackStarted(asset.name) },
                    onFailed = { onAssetFailed(asset.name) },
                    modifier = modifier
                )
                else -> UnavailableAssetPlaceholder(modifier)
            }
        }
        AssetType.VIDEO -> {
            if (localFile == null) {
                UnavailableAssetPlaceholder(modifier)
                return
            }
            VideoPlayer(
                file = localFile,
                playbackSessionKey = playbackSessionId,
                stopToken = videoStopToken,
                onPlaybackStarted = { onPlaybackStarted(asset.name) },
                onPlaybackEnded = { onVideoEnded(asset.name) },
                onRendererPulse = onVideoRendererPulse,
                onError = { onAssetFailed(asset.name) },
                modifier = modifier
            )
        }
        AssetType.URL -> {
            val remoteUrl = asset.remoteSourceUrl()
            if (remoteUrl.isNullOrBlank() && (localFile == null || !localFile.exists())) {
                UnavailableAssetPlaceholder(modifier)
                return
            }
            UrlPlayer(
                url = remoteUrl.orEmpty(),
                cachedFile = localFile,
                onLoadSuccess = { onUrlLoadSuccess(asset.name) },
                onLoadFailed = { onUrlLoadFailed(asset.name) },
                modifier = modifier
            )
        }
        AssetType.DOCUMENT -> {
            if (localFile == null || !localFile.exists()) {
                UnavailableAssetPlaceholder(modifier)
                return
            }
            DocumentPlayer(
                file = localFile,
                asset = asset,
                playbackSessionKey = playbackSessionId,
                onLoadSuccess = { onPlaybackStarted(asset.name) },
                onLoadFailed = { onAssetFailed(asset.name) },
                modifier = modifier
            )
        }
        else -> {
            // Legacy HTML (and any unknown type): skip without crashing the queue.
            LaunchedEffect(asset.id, playbackSessionId) {
                onAssetFailed(asset.name)
            }
            UnsupportedAssetPlaceholder(
                label = "Unsupported: ${asset.type}",
                subtitle = asset.name,
                modifier = modifier
            )
        }
    }
}

@Composable
fun UnavailableAssetPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = "Asset unavailable",
                tint = Color(0xFF6C63FF),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Asset unavailable", color = Color(0xFFB0B0C0), fontSize = 14.sp)
        }
    }
}

@Composable
fun UnsupportedAssetPlaceholder(
    label: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, color = Color(0xFFB0B0C0), fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = subtitle, color = Color(0xFF6C63FF), fontSize = 12.sp)
        }
    }
}

@Composable
fun ZonePlaceholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color(0xFFB0B0C0), fontSize = 14.sp)
    }
}
