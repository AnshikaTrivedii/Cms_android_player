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
import kotlinx.coroutines.delay
import java.io.File

/**
 * Full-screen video player using Media3 ExoPlayer.
 * Playback is capped at [configuredDurationSeconds] (Option B).
 * Advancement to the next asset is driven by [PlaybackViewModel], not video end.
 */
@Composable
fun VideoPlayer(
    file: File,
    configuredDurationSeconds: Int,
    playbackSessionKey: String = "",
    onPlaybackStarted: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuredDurationMs = configuredDurationSeconds.coerceAtLeast(1) * 1000L

    val exoPlayer = remember(file.absolutePath, playbackSessionKey) {
        var playbackStarted = false
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            setMediaItem(mediaItem)
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !playbackStarted) {
                        playbackStarted = true
                        onPlaybackStarted()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    onError()
                }
            })

            prepare()
        }
    }

    LaunchedEffect(file.absolutePath, configuredDurationMs, playbackSessionKey) {
        delay(configuredDurationMs)
        exoPlayer.pause()
        exoPlayer.stop()
    }

    DisposableEffect(exoPlayer) {
        onDispose {
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
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
