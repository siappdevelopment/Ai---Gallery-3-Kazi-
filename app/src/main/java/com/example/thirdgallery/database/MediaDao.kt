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
        "SELECT m.*, " +
        "(SELECT COUNT(*) FROM media c WHERE c.dayKey = m.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite AND (c.dateMs > m.dateMs OR (c.dateMs = m.dateMs AND c.uid > m.uid))) AS dayRank, " +
        "CASE WHEN NOT EXISTS (SELECT 1 FROM media c WHERE c.dayKey = m.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite AND (c.dateMs > m.dateMs OR (c.dateMs = m.dateMs AND c.uid > m.uid))) THEN " +
        "(SELECT c.uid FROM media c WHERE c.dayKey = m.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 1) END AS next1, " +
        "CASE WHEN NOT EXISTS (SELECT 1 FROM media c WHERE c.dayKey = m.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite AND (c.dateMs > m.dateMs OR (c.dateMs = m.dateMs AND c.uid > m.uid))) THEN " +
        "(SELECT c.uid FROM media c WHERE c.dayKey = m.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 2) END AS next2 " +
        "FROM media m WHERE m.mediaType IN (:types) AND m.isFavorite >= :minFavorite " +
        "ORDER BY m.dateMs DESC, m.uid DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun mosaicPage(types: List<Int>, minFavorite: Int, limit: Int, offset: Int): List<MosaicRow>

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

    @Query(
        "SELECT m.monthKey AS monthKey, COUNT(*) AS total, " +
            "(SELECT uid FROM media c WHERE c.monthKey = m.monthKey ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1) AS cover0" + ", " +
            "(SELECT uid FROM media c WHERE c.monthKey = m.monthKey ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 1) AS cover1" + ", " +
            "(SELECT uid FROM media c WHERE c.monthKey = m.monthKey ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 2) AS cover2" + ", " +
            "(SELECT uid FROM media c WHERE c.monthKey = m.monthKey ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 3) AS cover3" + ", " +
            "(SELECT uid FROM media c WHERE c.monthKey = m.monthKey ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 4) AS cover4" +
            " FROM media m GROUP BY m.monthKey ORDER BY m.monthKey DESC"
    )
    fun observeMonths(): LiveData<List<MonthRow>>

    @Query("SELECT COUNT(*) FROM media WHERE mediaType IN (:types) AND isFavorite >= :minFavorite")
    fun observeCount(types: List<Int>, minFavorite: Int): LiveData<Int>

    @Query("SELECT COUNT(*) FROM media")
    fun observeTotal(): LiveData<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE syncToken != :token")
    fun deleteStale(token: Long)

    @Query("DELETE FROM media")
    fun clear()
}
