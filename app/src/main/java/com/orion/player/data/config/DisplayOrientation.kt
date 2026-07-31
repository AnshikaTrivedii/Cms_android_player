package com.orion.player.data.config

/**
 * Screen orientation desired by the CMS for this device.
 */
enum class DisplayOrientation {
    LANDSCAPE,
    PORTRAIT;

    companion object {
        fun parse(raw: String?): DisplayOrientation {
            return when (raw?.trim()?.uppercase()) {
                "PORTRAIT" -> PORTRAIT
                else -> LANDSCAPE
            }
        }
    }
}
