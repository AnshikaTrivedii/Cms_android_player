package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted tickers for offline display (all tickers returned by last sync).
 */
@Entity(tableName = "cached_tickers")
data class CachedTickerEntity(
    @PrimaryKey val tickerId: String,
    val text: String,
    val scope: String,
    val position: String,
    val speed: String,
    val priority: String,
    val heightPercent: Int,
    val style: String,
    val backgroundColor: String,
    val textColor: String,
    val sortOrder: Int
)
