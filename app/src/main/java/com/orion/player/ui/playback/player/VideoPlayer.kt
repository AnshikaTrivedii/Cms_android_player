package com.orion.player.ui.playback.player

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.orion.player.R
import com.orion.player.ui.playback.LocalStretchToFit
import java.io.File

/**
 * Full-screen video player using Media3 ExoPlayer.
 * Slot timing is owned by [com.orion.player.ui.playback.PlaybackViewModel].
 *
 * Uses TextureView (via [R.layout.orion_player_view]) so frames participate in the
 * Compose draw pipeline. SurfaceView often shows black under Compose opaque layers.
 *
 * Stretch to Fit uses [AspectRatioFrameLayout.RESIZE_MODE_FILL] so the TextureView
 * occupies the full playback container and the video is scaled non-uniformly
 * (width → container width, height → container height). MediaCodec
 * [androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING] is not
 * used: it is ignored on TextureView and would crop rather than stretch.
 */
@Composable
fun VideoPlayer(
    file: File,
    playbackSessionKey: String = "",
    stopToken: Long = 0L,
    onPlaybackStarted: () -> Unit,
    onPlaybackEnded: () -> Unit = {},
    onRendererPulse: () -> Unit = {},
    onError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val stretchToFit = LocalStretchToFit.current
    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    val exoPlayer = remember(file.absolutePath, playbackSessionKey) {
        var readySignaled = false
        var endedSignaled = false
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            if (!readySignaled) {
                                readySignaled = true
                                Log.i(
                                    TAG,
                                    "video_ready file=${file.name} bytes=${file.length()} " +
                                        "session=$playbackSessionKey"
                                )
                                onPlaybackStarted()
                            }
                            onRendererPulse()
                            val size = videoSize
                            if (size.width > 0 && size.height > 0) {
                                videoWidth = size.width
                                videoHeight = size.height
                            }
                        }
                        Player.STATE_ENDED -> {
                            if (!endedSignaled) {
                                endedSignaled = true
                                onPlaybackEnded()
                            }
                        }
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        onRendererPulse()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(
                        TAG,
                        "video_error file=${file.absolutePath} bytes=${file.length()} " +
                            "exists=${file.exists()} code=${error.errorCodeName} msg=${error.message}",
                        error
                    )
                    onError()
                }
            })

            prepare()
        }
    }

    LaunchedEffect(stretchToFit, containerWidth, containerHeight, videoWidth, videoHeight) {
        logVideoStretch(
            stretchToFit = stretchToFit,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )
    }

    LaunchedEffect(stopToken) {
        if (stopToken > 0L && exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    DisposableEffect(file.absolutePath, playbackSessionKey) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx)
                    .inflate(R.layout.orion_player_view, null, false) as PlayerView)
                    .apply {
                        player = exoPlayer
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        applyStretchResizeMode(stretchToFit)
                    }
            },
            update = { playerView ->
                if (playerView.player !== exoPlayer) {
                    playerView.player = exoPlayer
                }
                playerView.applyStretchResizeMode(stretchToFit)
            },
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    containerWidth = size.width
                    containerHeight = size.height
                }
        )
    }
}

private fun PlayerView.applyStretchResizeMode(stretchToFit: Boolean) {
    val mode = if (stretchToFit) {
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    } else {
        AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    if (resizeMode != mode) {
        resizeMode = mode
    }
}

private fun logVideoStretch(
    stretchToFit: Boolean,
    containerWidth: Int,
    containerHeight: Int,
    videoWidth: Int,
    videoHeight: Int
) {
    if (containerWidth <= 0 || containerHeight <= 0) return
    val resizeMode = if (stretchToFit) "STRETCH" else "FIT"
    Log.i(
        STRETCH_TAG,
        "Asset Type = VIDEO Stretch To Fit = $stretchToFit " +
            "Container = ${containerWidth}x$containerHeight " +
            "Video = ${videoWidth}x$videoHeight " +
            "Resize Mode = $resizeMode"
    )
}

private const val TAG = "OrionPlayback"
private const val STRETCH_TAG = "OrionStretchToFit"
