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

    @Query("SELECT * FROM cached_assets ORDER BY queueIndex ASC")
    suspend fun getAssets(): List<CachedAssetEntity>

    @Query("DELETE FROM cached_assets")
    suspend fun clearAssets()

    @Query("DELETE FROM cached_playlist")
    suspend fun clearPlaylist()

    @Query("SELECT COUNT(*) FROM cached_assets WHERE localFilePath IS NOT NULL")
    suspend fun getCachedAssetCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTickers(tickers: List<CachedTickerEntity>)

    @Query("SELECT * FROM cached_tickers ORDER BY sortOrder ASC")
    suspend fun getTickers(): List<CachedTickerEntity>

    @Query("DELETE FROM cached_tickers")
    suspend fun clearTickers()

    @Transaction
    suspend fun replaceTickers(tickers: List<CachedTickerEntity>) {
        clearTickers()
        if (tickers.isNotEmpty()) {
            upsertTickers(tickers)
        }
    }

    @Transaction
    suspend fun replaceAll(
        playlist: CachedPlaylistEntity,
        assets: List<CachedAssetEntity>
    ) {
        upsertPlaylist(playlist)
        clearAssets()
        if (assets.isNotEmpty()) {
            upsertAssets(assets)
        }
    }
}
