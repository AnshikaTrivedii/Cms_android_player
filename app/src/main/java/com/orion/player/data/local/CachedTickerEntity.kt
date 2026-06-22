package com.orion.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Last synced ticker for offline display.
 */
@Entity(tableName = "cached_ticker")
data class CachedTickerEntity(
    @PrimaryKey val id: Int = 1,
    val tickerId: String?,
    val text: String?,
    val position: String?,
    val speed: String?,
    val backgroundColor: String?,
    val textColor: String?
)
