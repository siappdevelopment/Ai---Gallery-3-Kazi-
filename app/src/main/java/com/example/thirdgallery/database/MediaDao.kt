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

    @Query(
        "SELECT m.bucketId AS bucketId, m.bucketName AS bucketName, COUNT(*) AS total, " +
            "(SELECT uid FROM media c WHERE c.bucketId = m.bucketId ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1) AS cover0, " +
            "(SELECT uid FROM media c WHERE c.bucketId = m.bucketId ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 1) AS cover1, " +
            "(SELECT uid FROM media c WHERE c.bucketId = m.bucketId ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 2) AS cover2 " +
            "FROM media m WHERE m.bucketName != '' GROUP BY m.bucketId ORDER BY total DESC LIMIT :limit"
    )
    fun observeAlbums(limit: Int): LiveData<List<AlbumRow>>

    @Query("SELECT COUNT(*) FROM media")
    fun observeTotal(): LiveData<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE syncToken != :token")
    fun deleteStale(token: Long)

    @Query("DELETE FROM media")
    fun clear()
}
