package com.orion.player.data.sync

import android.util.Log
import com.orion.player.data.local.SecurePrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists last-acknowledged sync identity from the server.
 * Updated only after a fully verified sync (all downloads on disk).
 */
@Singleton
class SyncStateStore @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    var lastStoredRevision: String?
        get() = securePrefs.lastStoredRevision
        private set(value) { securePrefs.lastStoredRevision = value }

    var lastStoredPlaylistId: String?
        get() = securePrefs.lastStoredPlaylistId
        private set(value) { securePrefs.lastStoredPlaylistId = value }

    var lastStoredLayoutId: String?
        get() = securePrefs.lastStoredLayoutId
        private set(value) { securePrefs.lastStoredLayoutId = value }

    fun commit(
        revision: String?,
        playlistId: String?,
        layoutId: String?
    ) {
        revision?.takeIf { it.isNotBlank() }?.let { lastStoredRevision = it }
        lastStoredPlaylistId = playlistId
        lastStoredLayoutId = layoutId
        Log.i(
            TAG,
            "sync_state_committed revision=${revision.orEmpty()} playlistId=${playlistId.orEmpty()} layoutId=${layoutId.orEmpty()}"
        )
    }

    fun seedIfEmpty(revision: String?, playlistId: String?, layoutId: String?) {
        if (lastStoredRevision.isNullOrBlank() && !revision.isNullOrBlank()) {
            lastStoredRevision = revision
        }
        if (lastStoredPlaylistId.isNullOrBlank() && !playlistId.isNullOrBlank()) {
            lastStoredPlaylistId = playlistId
        }
        if (lastStoredLayoutId.isNullOrBlank() && !layoutId.isNullOrBlank()) {
            lastStoredLayoutId = layoutId
        }
    }

    fun clear() {
        lastStoredRevision = null
        lastStoredPlaylistId = null
        lastStoredLayoutId = null
    }

    companion object {
        private const val TAG = "OrionSync"
    }
}
