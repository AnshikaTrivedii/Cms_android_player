package com.orion.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for the Orion Player.
 * Currently holds only the Proof-of-Play log queue.
 */
@Database(
    entities = [PopLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class OrionDatabase : RoomDatabase() {
    abstract fun popLogDao(): PopLogDao
}
