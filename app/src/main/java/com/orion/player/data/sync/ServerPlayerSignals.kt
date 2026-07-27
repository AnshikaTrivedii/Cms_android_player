package com.orion.player.data.sync

import com.orion.player.data.enterprise.RemoteCommand
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
    val commands: List<RemoteCommand>? = null
) {
    fun forceSyncCommand(): RemoteCommand? =
        commands?.firstOrNull { isForceSyncType(it.type) }

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
                commands = response.commands
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
                commands = response.commands
            )

        fun from(response: DeviceReportResponse): ServerPlayerSignals =
            ServerPlayerSignals(
                syncIntervalSeconds = response.syncIntervalSeconds,
                initialSyncPending = response.initialSyncPending,
                initialSyncTimeoutSeconds = response.initialSyncTimeoutSeconds,
                popLogsExpected = response.popLogsExpected,
                features = response.features,
                commands = response.commands
            )

        fun isForceSyncType(type: String): Boolean =
            type.trim().lowercase(Locale.US).replace('-', '_') == "force_sync"
    }
}
