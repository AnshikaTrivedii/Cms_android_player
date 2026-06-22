package com.orion.player.ui.playback

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.ui.playback.player.HtmlPlayer
import com.orion.player.ui.playback.player.ImagePlayer
import com.orion.player.ui.playback.ticker.TickerOverlay
import com.orion.player.ui.playback.ticker.toAlignment
import com.orion.player.ui.playback.player.ImagePlayer
import com.orion.player.ui.playback.player.VideoPlayer

/**
 * Main playback screen — renders assets fullscreen with crossfade transitions.
 */
@Composable
fun PlaybackScreen(
    onUnpaired: () -> Unit,
    viewModel: PlaybackViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isUnpaired by viewModel.isUnpaired.collectAsState()

    // Handle unpair (401 response)
    LaunchedEffect(isUnpaired) {
        if (isUnpaired) {
            onUnpaired()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (val state = uiState) {
            is PlaybackUiState.Loading -> LoadingState()
            is PlaybackUiState.Downloading -> DownloadingState(state.current, state.total)
            is PlaybackUiState.WaitingForInitialDownload -> WaitingForInitialDownloadState(
                onRetry = { viewModel.retry() }
            )
            is PlaybackUiState.NoContent -> NoContentState()
            is PlaybackUiState.Playing -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    PlayingState(
                        state = state,
                        onAssetFailed = { viewModel.onAssetFailed(it) },
                        onPlaybackStarted = { viewModel.onPlaybackStarted(it) },
                        onUrlLoadSuccess = { viewModel.onUrlLoadSuccess(it) },
                        onUrlLoadFailed = { viewModel.onUrlLoadFailed(it) }
                    )
                    state.ticker?.let { ticker ->
                        TickerOverlay(
                            config = ticker,
                            modifier = Modifier.align(ticker.position.toAlignment())
                        )
                    }
                }
            }
            is PlaybackUiState.Error -> ErrorState(
                message = state.message,
                onRetry = { viewModel.retry() }
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = Color(0xFF6C63FF),
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Loading content...",
            color = Color(0xFFB0B0C0),
            fontSize = 16.sp
        )
    }
}

@Composable
private fun DownloadingState(current: Int, total: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = Color(0xFF6C63FF),
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Downloading assets...",
            color = Color.White,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$current / $total",
            color = Color(0xFFB0B0C0),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun WaitingForInitialDownloadState(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(48.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = "Waiting for content",
            tint = Color(0xFF6C63FF),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Waiting for Initial Content Download",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Connect to the internet and assign a playlist\nin the Orion CMS dashboard.",
            color = Color(0xFFB0B0C0),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF))
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Retry")
            Text("  Retry", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun NoContentState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(48.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Tv,
            contentDescription = "No content",
            tint = Color(0xFF6C63FF),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "No Content Assigned",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Please assign a playlist to this display\nin the Orion CMS dashboard.",
            color = Color(0xFFB0B0C0),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun PlayingState(
    state: PlaybackUiState.Playing,
    onAssetFailed: (String) -> Unit,
    onPlaybackStarted: (String) -> Unit,
    onUrlLoadSuccess: (String) -> Unit,
    onUrlLoadFailed: (String) -> Unit
) {
    Crossfade(
        targetState = state.currentIndex,
        animationSpec = tween(durationMillis = 800),
        label = "assetTransition"
    ) { _ ->
        val asset = state.asset
        val localFile = state.localFile

        when (asset.normalizedType()) {
            AssetType.IMAGE -> {
                if (localFile == null) {
                    UnavailableAssetPlaceholder()
                    return@Crossfade
                }
                ImagePlayer(file = localFile, modifier = Modifier.fillMaxSize())
            }
            AssetType.VIDEO -> {
                if (localFile == null) {
                    UnavailableAssetPlaceholder()
                    return@Crossfade
                }
                VideoPlayer(
                    file = localFile,
                    configuredDurationSeconds = asset.durationSeconds,
                    onPlaybackStarted = { onPlaybackStarted(asset.name) },
                    onError = { onAssetFailed(asset.name) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AssetType.HTML, AssetType.URL -> {
                if (localFile == null || !localFile.exists()) {
                    UnavailableAssetPlaceholder()
                    return@Crossfade
                }
                HtmlPlayer(
                    url = localFile.toURI().toString(),
                    onLoadSuccess = {
                        if (asset.normalizedType() == AssetType.URL) {
                            onUrlLoadSuccess(asset.name)
                        } else {
                            onPlaybackStarted(asset.name)
                        }
                    },
                    onLoadFailed = {
                        if (asset.normalizedType() == AssetType.URL) {
                            onUrlLoadFailed(asset.name)
                        } else {
                            onAssetFailed(asset.name)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AssetType.DOCUMENT -> {
                UnsupportedAssetPlaceholder(
                    label = "Document playback not supported",
                    subtitle = asset.name
                )
            }
            else -> {
                UnsupportedAssetPlaceholder(
                    label = "Unsupported: ${asset.type}",
                    subtitle = asset.name
                )
            }
        }
    }
}

@Composable
private fun UnavailableAssetPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
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
            Text(
                text = "Asset unavailable",
                color = Color(0xFFB0B0C0),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun UnsupportedAssetPlaceholder(label: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
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
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚠️",
            fontSize = 48.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Playback Error",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = Color(0xFFB0B0C0),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6C63FF)
            )
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Retry")
            Text("  Retry", modifier = Modifier.padding(start = 4.dp))
        }
    }
}
