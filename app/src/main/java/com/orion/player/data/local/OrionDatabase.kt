package com.orion.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for offline-first storage:
 * - Proof-of-Play log queue
 * - Cached playlist manifest + asset metadata
 * - Queued heartbeats
 */
@Database(
    entities = [
        PopLogEntity::class,
        CachedPlaylistEntity::class,
        CachedAssetEntity::class,
        CachedTickerEntity::class,
        QueuedHeartbeatEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class OrionDatabase : RoomDatabase() {
    abstract fun popLogDao(): PopLogDao
    abstract fun playlistCacheDao(): PlaylistCacheDao
    abstract fun heartbeatQueueDao(): HeartbeatQueueDao
}
