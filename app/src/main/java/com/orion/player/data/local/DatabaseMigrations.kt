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
