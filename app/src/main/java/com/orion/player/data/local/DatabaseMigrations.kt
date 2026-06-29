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

/**
 * Backend removed campaigns entirely (Playlist → PlaylistAsset → Asset).
 * Drop the now-unused campaignName columns from the local cache + PoP queue.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pop_logs_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                deviceName TEXT NOT NULL,
                playlistName TEXT NOT NULL,
                assetName TEXT NOT NULL,
                startTime TEXT NOT NULL,
                endTime TEXT NOT NULL,
                durationSeconds INTEGER NOT NULL,
                status TEXT NOT NULL,
                isSynced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO pop_logs_new (
                id, deviceName, playlistName, assetName, startTime, endTime, durationSeconds, status, isSynced
            )
            SELECT id, deviceName, playlistName, assetName, startTime, endTime, durationSeconds, status, isSynced
            FROM pop_logs
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS pop_logs")
        db.execSQL("ALTER TABLE pop_logs_new RENAME TO pop_logs")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_playlist_new (
                id INTEGER PRIMARY KEY NOT NULL,
                playlistId TEXT NOT NULL,
                playlistName TEXT NOT NULL,
                contentRevision TEXT,
                lastSyncTime INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO cached_playlist_new (
                id, playlistId, playlistName, contentRevision, lastSyncTime
            )
            SELECT id, playlistId, playlistName, contentRevision, lastSyncTime
            FROM cached_playlist
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS cached_playlist")
        db.execSQL("ALTER TABLE cached_playlist_new RENAME TO cached_playlist")
    }
}

/**
 * Tickers switched from a height enum (SMALL/MEDIUM/LARGE) to an integer heightPercent
 * (percentage of screen height) and gained a style theme. Rebuild cached_tickers.
 * Cached tickers are re-populated on the next sync, so historical rows can be dropped.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS cached_tickers")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_tickers (
                tickerId TEXT PRIMARY KEY NOT NULL,
                text TEXT NOT NULL,
                scope TEXT NOT NULL,
                position TEXT NOT NULL,
                speed TEXT NOT NULL,
                priority TEXT NOT NULL,
                heightPercent INTEGER NOT NULL DEFAULT 12,
                style TEXT NOT NULL DEFAULT 'CLASSIC',
                backgroundColor TEXT NOT NULL,
                textColor TEXT NOT NULL,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/** Multi-zone layout support: playbackMode, versions, and serialized layout JSON. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_playlist_new (
                id INTEGER PRIMARY KEY NOT NULL,
                playbackMode TEXT NOT NULL DEFAULT 'FULL_SCREEN',
                playlistId TEXT,
                playlistName TEXT,
                playlistVersion INTEGER,
                layoutVersion INTEGER,
                layoutJson TEXT,
                contentRevision TEXT,
                lastSyncTime INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO cached_playlist_new (
                id, playbackMode, playlistId, playlistName, playlistVersion,
                layoutVersion, layoutJson, contentRevision, lastSyncTime
            )
            SELECT id, 'FULL_SCREEN', playlistId, playlistName, NULL,
                NULL, NULL, contentRevision, lastSyncTime
            FROM cached_playlist
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS cached_playlist")
        db.execSQL("ALTER TABLE cached_playlist_new RENAME TO cached_playlist")
    }
}
