package com.orion.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HeartbeatQueueDao {

    @Insert
    suspend fun insert(heartbeat: QueuedHeartbeatEntity)

    @Query("SELECT * FROM queued_heartbeats WHERE isSynced = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 20): List<QueuedHeartbeatEntity>

    @Query("UPDATE queued_heartbeats SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM queued_heartbeats WHERE isSynced = 1")
    suspend fun deleteSynced()
}
