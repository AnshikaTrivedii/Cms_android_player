package com.orion.player.ui.playback.player

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.orion.player.R
import java.io.File

/**
 * Full-screen video player using Media3 ExoPlayer.
 * Slot timing is owned by [com.orion.player.ui.playback.PlaybackViewModel].
 *
 * Uses TextureView (via [R.layout.orion_player_view]) so frames participate in the
 * Compose draw pipeline. SurfaceView often shows black under Compose opaque layers.
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
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

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

private const val TAG = "OrionPlayback"
