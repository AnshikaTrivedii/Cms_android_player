package com.orion.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PlaylistCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: CachedPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssets(assets: List<CachedAssetEntity>)

    @Query("SELECT * FROM cached_playlist WHERE id = 1 LIMIT 1")
    suspend fun getPlaylist(): CachedPlaylistEntity?

    @Query("SELECT * FROM cached_assets ORDER BY position ASC")
    suspend fun getAssets(): List<CachedAssetEntity>

    @Query("DELETE FROM cached_assets WHERE assetId NOT IN (:assetIds)")
    suspend fun deleteAssetsNotIn(assetIds: List<String>)

    @Query("DELETE FROM cached_assets")
    suspend fun clearAssets()

    @Query("SELECT COUNT(*) FROM cached_assets WHERE localFilePath IS NOT NULL")
    suspend fun getCachedAssetCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTicker(ticker: CachedTickerEntity)

    @Query("SELECT * FROM cached_ticker WHERE id = 1 LIMIT 1")
    suspend fun getTicker(): CachedTickerEntity?

    @Query("DELETE FROM cached_ticker")
    suspend fun clearTicker()

    @Transaction
    suspend fun replaceAll(
        playlist: CachedPlaylistEntity,
        assets: List<CachedAssetEntity>
    ) {
        upsertPlaylist(playlist)
        val assetIds = assets.map { it.assetId }
        if (assetIds.isEmpty()) {
            clearAssets()
        } else {
            deleteAssetsNotIn(assetIds)
            upsertAssets(assets)
        }
    }
}
