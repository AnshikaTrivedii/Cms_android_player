package com.orion.player.ui.playback.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.File

/**
 * One ExoPlayer for the whole playlist. The visible video plays while the next
 * video, if any, is the upcoming item. The player is not rebuilt between spots.
 */
class WarmVideoEngine(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        pauseAtEndOfMediaItems = true
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    var onVisibleReady: (Long) -> Unit = {}
    var onHiddenReady: (Long) -> Unit = {}
    var onEnded: (Long) -> Unit = {}
    var onVisibleError: (Long) -> Unit = {}
    var onHiddenError: (Long) -> Unit = {}
    var onPulse: () -> Unit = {}

    private var visiblePath: String? = null
    private var hiddenPath: String? = null
    private var visiblePrep: Long = -1L
    private var hiddenPrep: Long = -1L
    private var endedForPrep: Long = Long.MIN_VALUE
    private var ignoreEndedForPath: String? = null
    private var queueAfterTransition = false
    private var hiddenFileAfterTransition: File? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val visible = visiblePath
                    if (visible != null && player.currentMediaItem?.mediaId == visible) {
                        onVisibleReady(visiblePrep)
                        onPulse()
                        notifyHiddenReadyIfQueued()
                    }
                    if (hiddenPath != null && visiblePath == null &&
                        player.currentMediaItem?.mediaId == hiddenPath
                    ) {
                        onHiddenReady(hiddenPrep)
                    }
                }
                Player.STATE_ENDED -> signalEnded()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            ) {
                signalEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (player.currentMediaItem?.mediaId == ignoreEndedForPath) {
                    ignoreEndedForPath = null
                }
                onPulse()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!queueAfterTransition) return
            if (player.currentMediaItem?.mediaId != visiblePath) return
            queueAfterTransition = false
            while (player.currentMediaItemIndex > 0) {
                player.removeMediaItem(0)
            }
            val hidden = hiddenFileAfterTransition
            hiddenFileAfterTransition = null
            trimAfterCurrent()
            if (hidden != null && queuedPath() != hidden.absolutePath) {
                player.addMediaItem(mediaItem(hidden))
            }
            notifyHiddenReadyIfQueued()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "video_error ${error.errorCodeName} ${error.message}", error)
            val current = player.currentMediaItem?.mediaId
            if (visiblePath != null && current == visiblePath) {
                onVisibleError(visiblePrep)
            } else if (hiddenPath != null) {
                onHiddenError(hiddenPrep)
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (eventTime.windowIndex > 0) notifyHiddenReadyIfQueued()
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
    }

    fun sync(visible: File?, visiblePrepId: Long, hidden: File?, hiddenPrepId: Long) {
        val nextVisible = visible?.absolutePath
        val nextHidden = hidden?.absolutePath
        if (nextVisible == visiblePath && nextHidden == hiddenPath && visiblePrep == visiblePrepId) {
            hiddenPrep = hiddenPrepId
            if (nextVisible != null && player.currentMediaItem?.mediaId == nextVisible) {
                player.playWhenReady = true
                if (!queueAfterTransition && hidden != null && queuedPath() != nextHidden) {
                    trimAfterCurrent()
                    player.addMediaItem(mediaItem(hidden))
                }
            }
            notifyHiddenReadyIfQueued()
            return
        }

        val queued = queuedPath()
        if (nextVisible != null && queued == nextVisible && player.currentMediaItem?.mediaId != nextVisible) {
            ignoreEndedForPath = nextVisible
            endedForPrep = visiblePrep
            queueAfterTransition = true
            hiddenFileAfterTransition = hidden
            visiblePrep = visiblePrepId
            hiddenPrep = hiddenPrepId
            visiblePath = nextVisible
            hiddenPath = nextHidden
            player.seekToNextMediaItem()
            player.playWhenReady = true
            notifyHiddenReadyIfQueued()
            return
        }

        visiblePrep = visiblePrepId
        hiddenPrep = hiddenPrepId
        visiblePath = nextVisible
        hiddenPath = nextHidden

        when {
            nextVisible != null && nextHidden != null -> ensurePair(visible!!, hidden!!)
            nextVisible != null -> ensureSoloVisible(visible!!)
            nextHidden != null -> ensureSoloHidden(hidden!!)
            else -> {
                player.pause()
                player.clearMediaItems()
            }
        }
    }

    fun pause() {
        if (player.isPlaying) player.pause()
    }

    fun release() {
        player.removeAnalyticsListener(analyticsListener)
        player.removeListener(listener)
        player.release()
    }

    private fun ensurePair(visible: File, hidden: File) {
        val current = player.currentMediaItem?.mediaId
        if (current == visible.absolutePath) {
            val second = queuedPath()
            if (second != hidden.absolutePath) {
                trimAfterCurrent()
                player.addMediaItem(mediaItem(hidden))
            }
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            notifyHiddenReadyIfQueued()
            return
        }
        player.setMediaItems(listOf(mediaItem(visible), mediaItem(hidden)), 0, 0L)
        player.prepare()
        player.playWhenReady = true
        notifyHiddenReadyIfQueued()
    }

    private fun ensureSoloVisible(visible: File) {
        if (player.currentMediaItem?.mediaId == visible.absolutePath && player.mediaItemCount == 1) {
            player.playWhenReady = true
            return
        }
        player.setMediaItem(mediaItem(visible))
        player.prepare()
        player.playWhenReady = true
    }

    private fun ensureSoloHidden(hidden: File) {
        if (player.currentMediaItem?.mediaId == hidden.absolutePath && !player.playWhenReady &&
            player.playbackState == Player.STATE_READY
        ) {
            onHiddenReady(hiddenPrep)
            return
        }
        if (player.currentMediaItem?.mediaId == hidden.absolutePath && player.mediaItemCount == 1) {
            player.playWhenReady = false
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            return
        }
        player.setMediaItem(mediaItem(hidden))
        player.playWhenReady = false
        player.prepare()
    }

    private fun queuedPath(): String? {
        val nextIndex = player.currentMediaItemIndex + 1
        return if (nextIndex < player.mediaItemCount) player.getMediaItemAt(nextIndex).mediaId else null
    }

    private fun trimAfterCurrent() {
        val keep = player.currentMediaItemIndex.coerceAtLeast(0)
        while (player.mediaItemCount > keep + 1) {
            player.removeMediaItem(player.mediaItemCount - 1)
        }
    }

    private fun notifyHiddenReadyIfQueued() {
        val hidden = hiddenPath ?: return
        if (queuedPath() == hidden) onHiddenReady(hiddenPrep)
    }

    private fun signalEnded() {
        val current = player.currentMediaItem?.mediaId
        if (current != null && current == ignoreEndedForPath) {
            ignoreEndedForPath = null
            return
        }
        val prep = visiblePrep
        val path = visiblePath ?: return
        if (current != path && player.mediaItemCount > 1) return
        if (endedForPrep == prep) return
        endedForPrep = prep
        onEnded(prep)
    }

    private fun mediaItem(file: File): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(file.absolutePath)
            .build()

    private companion object {
        const val TAG = "OrionPlayback"
    }
}
