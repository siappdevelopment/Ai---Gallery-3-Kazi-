package com.example.thirdgallery.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local database. Schemas are exported to app/schemas; when [version] is raised add a proper
 * Migration (no destructive fallback is configured on purpose).
 */
@Database(
    entities = [
        HighlightEntity::class,
        PersonEntity::class,
        PlaceEntity::class,
        SuggestionEntity::class,
        MediaEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        VaultItemEntity::class,
        VaultFolderEntity::class,
        VaultAlertEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun forYouDao(): ForYouDao

    abstract fun mediaDao(): MediaDao

    abstract fun albumDao(): AlbumDao

    abstract fun vaultDao(): VaultDao

    companion object {
        private const val NAME = "gallery.db"

        /** v2 adds the device media index used by the Gallery tab. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media` (" +
                        "`uid` INTEGER NOT NULL, " +
                        "`mediaId` INTEGER NOT NULL, " +
                        "`mediaType` INTEGER NOT NULL, " +
                        "`dateMs` INTEGER NOT NULL, " +
                        "`dayKey` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`isFavorite` INTEGER NOT NULL, " +
                        "`syncToken` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uid`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_dateMs` ON `media` (`dateMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_mediaType_dateMs` ON `media` (`mediaType`, `dateMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_dayKey` ON `media` (`dayKey`)")
            }
        }

        /** v3 adds the album (bucket) columns used by the "Your library" cards. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media` ADD COLUMN `bucketId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `media` ADD COLUMN `bucketName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_bucketId_dateMs` ON `media` (`bucketId`, `dateMs`)")
            }
        }

        /** v4 adds the month key used by the Timeline month and year views. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media` ADD COLUMN `monthKey` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_monthKey_dateMs` ON `media` (`monthKey`, `dateMs`)")
            }
        }

        /** v5 adds the app albums ("Add to album") and their items. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_items` (`albumId` INTEGER NOT NULL, `mediaUid` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `mediaUid`))"
                )
            }
        }

        /** v6 adds the file name used by the search. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media` ADD COLUMN `displayName` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7 adds the Photo vault items (files kept in the app's private vault folder). */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`mediaType` INTEGER NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`dateMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`isFavorite` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`sourceUid` INTEGER NOT NULL, " +
                        "`committed` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_addedAt` ON `vault_items` (`addedAt`)")
            }
        }

        /** v8 adds the vault folders, the break-in alerts and the folder of each vault item. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_alerts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`attemptedAt` INTEGER NOT NULL, `attempt` INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `folderId` INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
            }
    }
}
