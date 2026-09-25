package com.orion.player.ui.playback

import android.view.LayoutInflater
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.orion.player.R
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType
import com.orion.player.ui.playback.player.WarmVideoEngine

/**
 * Visible slot plus the single next item, prepared off-screen.
 * Video uses one [WarmVideoEngine] for the whole stage.
 */
@Composable
fun GaplessPlaybackStage(
    panes: List<PlaybackPane>,
    videoStopToken: Long,
    onPaneReady: (Long) -> Unit,
    onPaneFailed: (Long) -> Unit,
    onVideoEnded: (Long) -> Unit,
    onVideoRendererPulse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val stretchToFit = LocalStretchToFit.current
    val engine = remember { WarmVideoEngine(context) }
    val ready by rememberUpdatedState(onPaneReady)
    val failed by rememberUpdatedState(onPaneFailed)
    val ended by rememberUpdatedState(onVideoEnded)
    val pulse by rememberUpdatedState(onVideoRendererPulse)

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    val visible = panes.firstOrNull { it.visible }
    val hidden = panes.firstOrNull { !it.visible }
    val visibleVideo = visible?.takeIf { it.asset.normalizedType() == AssetType.VIDEO && !it.showError }
    val hiddenVideo = hidden?.takeIf { it.asset.normalizedType() == AssetType.VIDEO && !it.showError }

    SideEffectBindings(engine, ready, failed, ended, pulse)

    LaunchedEffect(
        visibleVideo?.localFile?.absolutePath,
        visibleVideo?.prepId,
        hiddenVideo?.localFile?.absolutePath,
        hiddenVideo?.prepId
    ) {
        engine.sync(
            visible = visibleVideo?.localFile,
            visiblePrepId = visibleVideo?.prepId ?: -1L,
            hidden = hiddenVideo?.localFile,
            hiddenPrepId = hiddenVideo?.prepId ?: -1L
        )
    }

    LaunchedEffect(videoStopToken) {
        if (videoStopToken > 0L) engine.pause()
    }

    Box(modifier = modifier.fillMaxSize()) {
        VideoSurface(
            engine = engine,
            stretchToFit = stretchToFit,
            inFront = visibleVideo != null,
            modifier = Modifier.fillMaxSize()
        )
        panes.forEach { pane ->
            val isVideo = pane.asset.normalizedType() == AssetType.VIDEO && !pane.showError
            key(pane.paneId) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (pane.visible && !isVideo) 1f else 0f)
            ) {
                if (pane.showError || isVideo) {
                    if (pane.showError && pane.visible) {
                        UnavailableAssetPlaceholder(Modifier.fillMaxSize())
                    }
                } else {
                    AssetPlayback(
                        asset = pane.asset,
                        localFile = pane.localFile,
                        playbackSessionId = pane.prepId.toString(),
                        modifier = Modifier.fillMaxSize(),
                        onAssetFailed = { _ -> failed(pane.prepId) },
                        onPlaybackStarted = { _ -> ready(pane.prepId) },
                        onUrlLoadSuccess = { _ -> ready(pane.prepId) },
                        onUrlLoadFailed = { _ -> ready(pane.prepId) }
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun SideEffectBindings(
    engine: WarmVideoEngine,
    onPaneReady: (Long) -> Unit,
    onPaneFailed: (Long) -> Unit,
    onVideoEnded: (Long) -> Unit,
    onPulse: () -> Unit
) {
    engine.onVisibleReady = onPaneReady
    engine.onHiddenReady = onPaneReady
    engine.onVisibleError = onPaneFailed
    engine.onHiddenError = onPaneFailed
    engine.onEnded = onVideoEnded
    engine.onPulse = onPulse
}

@Composable
private fun VideoSurface(
    engine: WarmVideoEngine,
    stretchToFit: Boolean,
    inFront: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.orion_player_view, null, false) as PlayerView)
                .apply {
                    player = engine.player
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    resizeMode = if (stretchToFit) {
                        AspectRatioFrameLayout.RESIZE_MODE_FILL
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
        },
        update = { playerView ->
            if (playerView.player !== engine.player) playerView.player = engine.player
            val mode = if (stretchToFit) {
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            if (playerView.resizeMode != mode) playerView.resizeMode = mode
        },
        onRelease = { it.player = null },
        modifier = modifier.zIndex(if (inFront) 2f else 0f)
    )
}
