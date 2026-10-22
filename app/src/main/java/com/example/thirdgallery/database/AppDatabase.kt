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
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun forYouDao(): ForYouDao

    abstract fun mediaDao(): MediaDao

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

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
