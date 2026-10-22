package com.example.thirdgallery.database

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaDao {

    /** Newest first; the uid tie-break keeps the order stable for items with the same time. */
    @Query(
        "SELECT * FROM media WHERE mediaType IN (:types) AND isFavorite >= :minFavorite " +
            "ORDER BY dateMs DESC, uid DESC"
    )
    fun pagingSource(types: List<Int>, minFavorite: Int): PagingSource<Int, MediaEntity>

    @Query(
        "SELECT dayKey, COUNT(*) AS total FROM media WHERE mediaType IN (:types) " +
            "AND isFavorite >= :minFavorite GROUP BY dayKey"
    )
    fun observeDayCounts(types: List<Int>, minFavorite: Int): LiveData<List<DayCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE syncToken != :token")
    fun deleteStale(token: Long)

    @Query("DELETE FROM media")
    fun clear()
}
