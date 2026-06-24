package com.orion.player.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_playlist (
                id INTEGER PRIMARY KEY NOT NULL,
                playlistId TEXT NOT NULL,
                playlistName TEXT NOT NULL,
                campaignName TEXT,
                contentRevision TEXT,
                lastSyncTime INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_assets (
                assetId TEXT PRIMARY KEY NOT NULL,
                assetName TEXT NOT NULL,
                assetType TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                durationSeconds INTEGER NOT NULL,
                position INTEGER NOT NULL,
                downloadUrl TEXT,
                fileSize INTEGER NOT NULL,
                remoteUrl TEXT,
                localFilePath TEXT,
                fileVersion TEXT NOT NULL,
                downloadTimestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queued_heartbeats (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                cpu INTEGER NOT NULL,
                ram INTEGER NOT NULL,
                temp INTEGER NOT NULL,
                currentContent TEXT,
                recordedAt INTEGER NOT NULL,
                isSynced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_ticker (
                id INTEGER PRIMARY KEY NOT NULL,
                tickerId TEXT,
                text TEXT,
                position TEXT,
                speed TEXT,
                backgroundColor TEXT,
                textColor TEXT
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS cached_ticker")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_tickers (
                tickerId TEXT PRIMARY KEY NOT NULL,
                text TEXT NOT NULL,
                scope TEXT NOT NULL,
                position TEXT NOT NULL,
                speed TEXT NOT NULL,
                priority TEXT NOT NULL,
                backgroundColor TEXT NOT NULL,
                textColor TEXT NOT NULL,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE cached_tickers
            ADD COLUMN height TEXT NOT NULL DEFAULT 'MEDIUM'
            """.trimIndent()
        )
    }
}
