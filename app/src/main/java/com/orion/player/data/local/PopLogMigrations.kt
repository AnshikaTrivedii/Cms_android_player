package com.orion.player.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pop_logs_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                deviceName TEXT NOT NULL,
                playlistName TEXT NOT NULL,
                campaignName TEXT NOT NULL,
                assetName TEXT NOT NULL,
                startTime TEXT NOT NULL,
                endTime TEXT NOT NULL,
                durationSeconds INTEGER NOT NULL,
                status TEXT NOT NULL,
                isSynced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS pop_logs")
        db.execSQL("ALTER TABLE pop_logs_new RENAME TO pop_logs")
    }
}
