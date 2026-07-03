package com.orion.player.ui.playback.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Full-screen video player using Media3 ExoPlayer.
 * Slot timing is owned by [com.orion.player.ui.playback.PlaybackViewModel].
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

    val exoPlayer = remember(file.absolutePath, playbackSessionKey) {
        var readySignaled = false
        var endedSignaled = false
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            setMediaItem(mediaItem)
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            if (!readySignaled) {
                                readySignaled = true
                                onPlaybackStarted()
                            }
                            onRendererPulse()
                        }
                        Player.STATE_ENDED -> {
                            if (!endedSignaled) {
                                endedSignaled = true
                                onPlaybackEnded()
                            }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        onRendererPulse()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    onError()
                }
            })

            prepare()
        }
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { playerView ->
                if (playerView.player !== exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
