package com.orion.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for the Proof-of-Play log queue.
 */
@Dao
interface PopLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: PopLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<PopLogEntity>)

    @Query("SELECT * FROM pop_logs WHERE isSynced = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 50): List<PopLogEntity>

    @Query("UPDATE pop_logs SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM pop_logs WHERE isSynced = 1")
    suspend fun deleteSynced()

    @Query("SELECT COUNT(*) FROM pop_logs WHERE isSynced = 0")
    suspend fun getUnsyncedCount(): Int
}
