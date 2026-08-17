package com.orion.player.data.sync

import com.orion.player.data.enterprise.RemoteCommand
import com.orion.player.data.remote.CacheCommandInfo
import com.orion.player.data.remote.DeviceReportResponse
import com.orion.player.data.remote.HeartbeatResponse
import com.orion.player.data.remote.PlayerFeatures
import com.orion.player.data.remote.SyncResponse
import java.util.Locale

/**
 * Normalized server hints from heartbeat, sync, and device-report responses.
 */
data class ServerPlayerSignals(
    val syncRequired: Boolean? = null,
    val contentRevision: String? = null,
    val syncIntervalSeconds: Int? = null,
    val revisionPollIntervalSeconds: Int? = null,
    val initialSyncPending: Boolean? = null,
    val initialSyncTimeoutSeconds: Int? = null,
    val popLogsExpected: Boolean? = null,
    val features: PlayerFeatures? = null,
    val commands: List<RemoteCommand>? = null,
    val cacheCommand: CacheCommandInfo? = null
) {
    fun forceSyncCommand(): RemoteCommand? =
        mergedCommands().firstOrNull { isForceSyncType(it.type) }

    fun mergedCommands(): List<RemoteCommand> {
        val fromList = commands.orEmpty()
        val fromCache = cacheCommand
            ?.takeIf { it.command.isNotBlank() }
            ?.let { RemoteCommand(id = it.id, type = it.command) }
            ?: return fromList
        val duplicate = fromList.any { existing ->
            (!existing.id.isNullOrBlank() && existing.id == fromCache.id) ||
                (isForceSyncType(existing.type) && isForceSyncType(fromCache.type))
        }
        return if (duplicate) fromList else fromList + fromCache
    }

    fun commandsExcludingForceSync(): List<RemoteCommand> =
        mergedCommands().filterNot { isForceSyncType(it.type) }

    companion object {
        fun from(response: HeartbeatResponse): ServerPlayerSignals =
            ServerPlayerSignals(
                syncRequired = response.syncRequired,
                contentRevision = response.contentRevision,
                syncIntervalSeconds = response.syncIntervalSeconds,
                revisionPollIntervalSeconds = response.revisionPollIntervalSeconds,
                initialSyncPending = response.initialSyncPending,
                initialSyncTimeoutSeconds = response.initialSyncTimeoutSeconds,
                popLogsExpected = response.popLogsExpected,
                features = response.features,
                commands = response.commands,
                cacheCommand = response.cacheCommand
            )

        fun from(response: SyncResponse): ServerPlayerSignals =
            ServerPlayerSignals(
                syncRequired = response.syncRequired,
                contentRevision = response.contentRevision,
                syncIntervalSeconds = response.syncIntervalSeconds,
                revisionPollIntervalSeconds = response.revisionPollIntervalSeconds,
                initialSyncPending = response.initialSyncPending,
                initialSyncTimeoutSeconds = response.initialSyncTimeoutSeconds,
                popLogsExpected = response.popLogsExpected,
                features = response.features,
                commands = response.commands,
                cacheCommand = response.cacheCommand
            )

        fun from(response: DeviceReportResponse): ServerPlayerSignals =
            ServerPlayerSignals(
                syncIntervalSeconds = response.syncIntervalSeconds,
                initialSyncPending = response.initialSyncPending,
                initialSyncTimeoutSeconds = response.initialSyncTimeoutSeconds,
                popLogsExpected = response.popLogsExpected,
                features = response.features,
                commands = response.commands,
                cacheCommand = response.cacheCommand
            )

        fun isForceSyncType(type: String): Boolean =
            type.trim().lowercase(Locale.US).replace('-', '_') == "force_sync"
    }
}
