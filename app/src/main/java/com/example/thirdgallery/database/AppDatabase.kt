package com.example.thirdgallery.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun forYouDao(): ForYouDao

    companion object {
        private const val NAME = "gallery.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
