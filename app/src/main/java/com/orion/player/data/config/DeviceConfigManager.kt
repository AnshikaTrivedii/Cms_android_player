package com.orion.player.data.config

import android.util.Log
import com.orion.player.data.local.SecurePrefs
import com.orion.player.data.remote.DisplayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds CMS-managed display settings (stretch + orientation) and exposes them
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

    private val _configVersion = MutableStateFlow(securePrefs.displayConfigVersion)

    /**
     * Apply config from heartbeat / sync / device-report.
     * Accepts either top-level fields or nested [display].
     */
    fun applyFromServer(
        configVersion: Int?,
        stretchToFit: Boolean?,
        orientation: String?,
        display: DisplayConfig?
    ) {
        val nextStretch = stretchToFit ?: display?.stretchToFit
        val nextOrientation = orientation ?: display?.orientation
        val version = configVersion

        if (version != null && version == _configVersion.value &&
            nextStretch == null && nextOrientation == null
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
        if (version != null && version != _configVersion.value) {
            _configVersion.value = version
            securePrefs.displayConfigVersion = version
            changed = true
        }
        if (changed) {
            Log.i(
                TAG,
                "Device config applied version=${_configVersion.value} " +
                    "stretch=${_stretchToFit.value} orientation=${_orientation.value}"
            )
        }
    }

    companion object {
        private const val TAG = "OrionDeviceConfig"
    }
}
