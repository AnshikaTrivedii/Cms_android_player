package com.orion.player.data.config

import android.util.Log
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.DisplayConfig
import com.orion.player.data.remote.PlayerFeatures
import com.orion.player.data.remote.PlayerPlaybackDurations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds CMS-managed display + playback-duration settings and exposes them
 * as observable state so playback can update without restarting the app.
 */
@Singleton
class DeviceConfigManager @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    private val _stretchToFit = MutableStateFlow(securePrefs.stretchToFit)
    val stretchToFit: StateFlow<Boolean> = _stretchToFit.asStateFlow()

    private val _orientation = MutableStateFlow(
        DisplayOrientation.parse(securePrefs.desiredOrientation)
    )
    val orientation: StateFlow<DisplayOrientation> = _orientation.asStateFlow()

    private val _playbackDurations = MutableStateFlow(securePrefs.playbackDurations())
    val playbackDurations: StateFlow<DevicePlaybackDurations> = _playbackDurations.asStateFlow()

    private val _configVersion = MutableStateFlow(securePrefs.displayConfigVersion)

    private val _tickerEnabled = MutableStateFlow(securePrefs.tickerEnabled)
    val tickerEnabled: StateFlow<Boolean> = _tickerEnabled.asStateFlow()

    /**
     * Apply config from heartbeat / sync / revision / device-report.
     * Accepts top-level fields, nested [display], and nested/top-level [playback].
     * Always refreshes cached durations when the server sends them (no stale cache).
     */
    fun applyFromServer(
        configVersion: Int?,
        stretchToFit: Boolean?,
        orientation: String?,
        display: DisplayConfig?,
        playback: PlayerPlaybackDurations? = null,
        defaultImageDuration: Int? = null,
        defaultDocumentDuration: Int? = null,
        defaultUrlDuration: Int? = null,
        defaultVideoDuration: Int? = null
    ) {
        val nextStretch = stretchToFit ?: display?.stretchToFit
        val nextOrientation = orientation ?: display?.orientation
        val nestedPlayback = playback ?: display?.playback
        val nextImage = nestedPlayback?.imageDuration
            ?: nestedPlayback?.defaultImageDuration
            ?: defaultImageDuration
            ?: display?.defaultImageDuration
        val nextDocument = nestedPlayback?.documentDuration
            ?: nestedPlayback?.defaultDocumentDuration
            ?: defaultDocumentDuration
            ?: display?.defaultDocumentDuration
        val nextUrl = nestedPlayback?.urlDuration
            ?: nestedPlayback?.defaultUrlDuration
            ?: defaultUrlDuration
            ?: display?.defaultUrlDuration
        val nextVideo = nestedPlayback?.videoDuration
            ?: nestedPlayback?.defaultVideoDuration
            ?: defaultVideoDuration
            ?: display?.defaultVideoDuration
        val version = configVersion

        val hasDurationUpdate =
            nextImage != null || nextDocument != null || nextUrl != null || nextVideo != null

        Log.d(
            TAG,
            "applyFromServer version=$version " +
                "playback=$nestedPlayback " +
                "topLevel=($defaultImageDuration,$defaultDocumentDuration," +
                "$defaultUrlDuration,$defaultVideoDuration) " +
                "resolved=($nextImage,$nextDocument,$nextUrl,$nextVideo)"
        )

        if (version != null && version == _configVersion.value &&
            nextStretch == null && nextOrientation == null && !hasDurationUpdate
        ) {
            return
        }

        var changed = false
        if (nextStretch != null && nextStretch != _stretchToFit.value) {
            _stretchToFit.value = nextStretch
            securePrefs.stretchToFit = nextStretch
            changed = true
            Log.i(TAG, "stretchToFit=$nextStretch")
        }
        if (nextOrientation != null) {
            val parsed = DisplayOrientation.parse(nextOrientation)
            if (parsed != _orientation.value) {
                _orientation.value = parsed
                securePrefs.desiredOrientation = parsed.name
                changed = true
                Log.i(TAG, "orientation=${parsed.name}")
            }
        }
        if (hasDurationUpdate) {
            val previous = _playbackDurations.value
            val next = DevicePlaybackDurations.sanitize(
                image = nextImage ?: previous.imageSeconds,
                document = nextDocument ?: previous.documentSeconds,
                url = nextUrl ?: previous.urlSeconds,
                video = nextVideo ?: previous.videoSeconds
            )
            // Always refresh cache after a successful server payload — never keep stale values.
            _playbackDurations.value = next
            securePrefs.defaultImageDurationSeconds = next.imageSeconds
            securePrefs.defaultDocumentDurationSeconds = next.documentSeconds
            securePrefs.defaultUrlDurationSeconds = next.urlSeconds
            securePrefs.defaultVideoDurationSeconds = next.videoSeconds
            securePrefs.playbackDurationsCached = true
            DevicePlaybackDurationLogger.settingsDownloaded(next, source = "cms")
            DevicePlaybackDurationLogger.settingsStored(next)
            if (next != previous) {
                DevicePlaybackDurationLogger.settingsUpdated(previous, next)
                changed = true
            }
        }
        if (version != null && version != _configVersion.value) {
            _configVersion.value = version
            securePrefs.displayConfigVersion = version
            changed = true
        }
        if (changed) {
            Log.i(
                TAG,
                "Device config applied version=${_configVersion.value} " +
                    "stretch=${_stretchToFit.value} orientation=${_orientation.value} " +
                    "image=${_playbackDurations.value.imageSeconds}s " +
                    "document=${_playbackDurations.value.documentSeconds}s " +
                    "url=${_playbackDurations.value.urlSeconds}s " +
                    "video=${_playbackDurations.value.videoSeconds?.let { "${it}s" } ?: "natural-end"}"
            )
        }
    }

    /**
     * `features.ticker === false` disables overlay and zone tickers. Default is true.
     * A missing `ticker` field leaves the current value unchanged.
     */
    fun applyFeatures(features: PlayerFeatures?) {
        val enabled = features?.ticker ?: return
        if (enabled == _tickerEnabled.value) return
        _tickerEnabled.value = enabled
        securePrefs.tickerEnabled = enabled
        Log.i(TAG, "features.ticker=$enabled")
    }

    companion object {
        private const val TAG = "OrionDeviceConfig"
    }
}
