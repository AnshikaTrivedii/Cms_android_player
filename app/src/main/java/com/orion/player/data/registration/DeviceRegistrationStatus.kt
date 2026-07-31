package com.orion.player.data.registration

/**
 * CMS device registration lifecycle as reported to the player.
 */
enum class DeviceRegistrationStatus {
    REGISTERED,
    UNREGISTERED,
    DELETED;

    companion object {
        fun parse(raw: String?): DeviceRegistrationStatus? {
            if (raw.isNullOrBlank()) return null
            return when (raw.trim().uppercase()) {
                "REGISTERED", "OK", "PAIRED" -> REGISTERED
                "UNREGISTERED", "UNPAIRED" -> UNREGISTERED
                "DELETED", "REMOVED" -> DELETED
                else -> null
            }
        }
    }
}
