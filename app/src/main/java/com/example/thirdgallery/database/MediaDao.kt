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

    /** Journal: one row per day (the page of days first, then the per-day lookups only for those days). */
    @Query(
        "SELECT d.dayKey AS dayKey, d.total AS total, " +
            "(SELECT c.uid FROM media c WHERE c.dayKey = d.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1) AS heroUid, " +
            "(SELECT c.bucketName FROM media c WHERE c.dayKey = d.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1) AS place, " +
            "(SELECT group_concat(s.uid) FROM (SELECT c.uid AS uid FROM media c WHERE c.dayKey = d.dayKey AND c.mediaType IN (:types) AND c.isFavorite >= :minFavorite ORDER BY c.dateMs DESC, c.uid DESC LIMIT 40 OFFSET 1) s) AS strip " +
            "FROM (SELECT m.dayKey AS dayKey, COUNT(*) AS total FROM media m WHERE m.mediaType IN (:types) AND m.isFavorite >= :minFavorite " +
            "GROUP BY m.dayKey ORDER BY m.dayKey DESC LIMIT :limit OFFSET :offset) d ORDER BY d.dayKey DESC"
    )
    suspend fun journalPage(types: List<Int>, minFavorite: Int, limit: Int, offset: Int): List<JournalDayRow>

    /** Every item of one day under the given filter, newest first (Add to album / Share). */
    @Query(
        "SELECT uid FROM media WHERE dayKey = :dayKey AND mediaType IN (:types) AND isFavorite >= :minFavorite " +
            "ORDER BY dateMs DESC, uid DESC"
    )
    fun uidsOfDay(dayKey: Long, types: List<Int>, minFavorite: Int): List<Long>

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

    /** Every device folder with its size, newest item date and four newest items (Albums tab). */
    @Query(
        "SELECT m.bucketId AS id, m.bucketName AS name, COUNT(*) AS total, MAX(m.dateMs) AS lastDate, " +
            "(SELECT c.uid FROM media c WHERE c.bucketId = m.bucketId ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1) AS cover0, " +
            "(SELECT c.uid FROM media c WHERE c.bucketId = m.bucketId ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 1) AS cover1, " +
            "(SELECT c.uid FROM media c WHERE c.bucketId = m.bucketId ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 2) AS cover2, " +
            "(SELECT c.uid FROM media c WHERE c.bucketId = m.bucketId ORDER BY c.dateMs DESC, c.uid DESC LIMIT 1 OFFSET 3) AS cover3 " +
            "FROM media m WHERE m.bucketName != '' GROUP BY m.bucketId"
    )
    fun observeDeviceAlbums(): LiveData<List<AlbumCardRow>>

    /**
     * Items of a smart collection / device folder: [bucketId] 0 = any folder, [nameLike] '' = any
     * folder name, [since] 0 = any date.
     */
    @Query(
        "SELECT * FROM media WHERE (:bucketId = 0 OR bucketId = :bucketId) AND mediaType IN (:types) " +
            "AND isFavorite >= :minFavorite AND dateMs >= :since AND (:nameLike = '' OR bucketName LIKE :nameLike) " +
            "ORDER BY dateMs DESC, uid DESC"
    )
    fun collectionSource(bucketId: Long, types: List<Int>, minFavorite: Int, since: Long, nameLike: String): PagingSource<Int, MediaEntity>

    @Query(
        "SELECT COUNT(*) FROM media WHERE (:bucketId = 0 OR bucketId = :bucketId) AND mediaType IN (:types) " +
            "AND isFavorite >= :minFavorite AND dateMs >= :since AND (:nameLike = '' OR bucketName LIKE :nameLike)"
    )
    fun observeCollectionCount(bucketId: Long, types: List<Int>, minFavorite: Int, since: Long, nameLike: String): LiveData<Int>

    /** Items of an app album, most recently added first (items deleted from the device drop out). */
    @Query(
        "SELECT m.* FROM media m INNER JOIN album_items i ON i.mediaUid = m.uid WHERE i.albumId = :albumId " +
            "ORDER BY i.addedAt DESC, m.uid DESC"
    )
    fun userAlbumSource(albumId: Long): PagingSource<Int, MediaEntity>

    @Query("SELECT COUNT(*) FROM album_items i INNER JOIN media m ON m.uid = i.mediaUid WHERE i.albumId = :albumId")
    fun observeUserAlbumCount(albumId: Long): LiveData<Int>

    @Query("SELECT * FROM media WHERE (monthKey / 12 = :year) ORDER BY dateMs DESC, uid DESC")
    fun yearSource(year: Int): PagingSource<Int, MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE (monthKey / 12 = :year)")
    fun observeYearCount(year: Int): LiveData<Int>

    @Query("SELECT * FROM media WHERE monthKey = :monthKey ORDER BY dateMs DESC, uid DESC")
    fun monthSource(monthKey: Long): PagingSource<Int, MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE monthKey = :monthKey")
    fun observeMonthCount(monthKey: Long): LiveData<Int>

    @Query("SELECT COUNT(*) FROM media")
    fun observeTotal(): LiveData<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE syncToken != :token")
    fun deleteStale(token: Long)

    @Query("DELETE FROM media")
    fun clear()

    /** The ordered ids of a device folder / smart collection (the photo viewer's swipe list). */
    @Query(
        "SELECT uid FROM media WHERE (:bucketId = 0 OR bucketId = :bucketId) AND mediaType IN (:types) " +
            "AND isFavorite >= :minFavorite AND dateMs >= :since AND (:nameLike = '' OR bucketName LIKE :nameLike) " +
            "ORDER BY dateMs DESC, uid DESC"
    )
    fun collectionUids(bucketId: Long, types: List<Int>, minFavorite: Int, since: Long, nameLike: String): List<Long>

    @Query(
        "SELECT m.uid FROM media m INNER JOIN album_items i ON i.mediaUid = m.uid WHERE i.albumId = :albumId " +
            "ORDER BY i.addedAt DESC, m.uid DESC"
    )
    fun userAlbumUids(albumId: Long): List<Long>

    @Query("SELECT uid FROM media WHERE (monthKey / 12 = :year) ORDER BY dateMs DESC, uid DESC")
    fun yearUids(year: Int): List<Long>

    @Query("SELECT uid FROM media WHERE monthKey = :monthKey ORDER BY dateMs DESC, uid DESC")
    fun monthUids(monthKey: Long): List<Long>

    @Query("SELECT * FROM media WHERE uid = :uid")
    fun byUid(uid: Long): MediaEntity?

    // For you highlights (all by the date the media was added)

    @Query(
        "SELECT uid, isFavorite, dateMs, mediaType, durationMs, " +
            "(bucketName LIKE '%screenshot%' OR displayName LIKE '%screenshot%') AS isShot, " +
            "(bucketName = 'Camera' COLLATE NOCASE) AS isCamera " +
            "FROM media WHERE addedMs >= :from AND addedMs < :to"
    )
    fun candidatesAdded(from: Long, to: Long): List<HighlightCandidate>

    @Query(
        "SELECT uid, isFavorite, dateMs, mediaType, durationMs, " +
            "(bucketName LIKE '%screenshot%' OR displayName LIKE '%screenshot%') AS isShot, " +
            "(bucketName = 'Camera' COLLATE NOCASE) AS isCamera " +
            "FROM media WHERE addedDay = :day"
    )
    fun candidatesAddedOn(day: Long): List<HighlightCandidate>

    /** Cheap totals of a period, to find the most relevant one without loading its items. */
    @Query(
        "SELECT COUNT(*) AS total, COALESCE(SUM(isFavorite), 0) AS favorites, " +
            "COALESCE(SUM(CASE WHEN bucketName LIKE '%screenshot%' OR displayName LIKE '%screenshot%' THEN 0 ELSE 1 END), 0) AS plain " +
            "FROM media WHERE addedMs >= :from AND addedMs < :to"
    )
    fun periodStats(from: Long, to: Long): PeriodStats

    @Query("SELECT COUNT(*) FROM media WHERE addedMs >= :from AND addedMs < :to")
    fun countAdded(from: Long, to: Long): Int

    /** Everything added in a period, for the highlight detail screen. */
    @Query("SELECT * FROM media WHERE addedMs >= :from AND addedMs < :to ORDER BY dateMs DESC, uid DESC")
    fun mediaAdded(from: Long, to: Long): List<MediaEntity>

    /** Totals of every day before [today] (and not older than [minDay]) with more than 5 items added. */
    @Query(
        "SELECT addedDay AS day, COUNT(*) AS total, COALESCE(SUM(isFavorite), 0) AS favorites, " +
            "COALESCE(SUM(CASE WHEN bucketName LIKE '%screenshot%' OR displayName LIKE '%screenshot%' THEN 0 ELSE 1 END), 0) AS plain " +
            "FROM media WHERE addedDay >= :minDay AND addedDay < :today GROUP BY addedDay HAVING COUNT(*) > 5"
    )
    fun busyDays(minDay: Long, today: Long): List<DayStats>

    @Query("SELECT * FROM media WHERE uid IN (:uids)")
    fun byUids(uids: List<Long>): List<MediaEntity>

    @Query("UPDATE media SET isFavorite = :favorite WHERE uid = :uid")
    fun setFavorite(uid: Long, favorite: Boolean)

    @Query("DELETE FROM media WHERE uid = :uid")
    fun deleteByUid(uid: Long)

    @Query("DELETE FROM media WHERE uid IN (:uids)")
    fun deleteByUids(uids: List<Long>)

    @Query("UPDATE media SET isFavorite = :favorite WHERE uid IN (:uids)")
    fun setFavorites(uids: List<Long>, favorite: Boolean)

    @Query("SELECT * FROM media WHERE displayName LIKE '%' || :pattern || '%' ESCAPE '\\' ORDER BY dateMs DESC, uid DESC")
    fun searchSource(pattern: String): PagingSource<Int, MediaEntity>

    @Query("SELECT uid FROM media WHERE displayName LIKE '%' || :pattern || '%' ESCAPE '\\' ORDER BY dateMs DESC, uid DESC")
    fun searchUids(pattern: String): List<Long>

    @Query("SELECT COUNT(*) FROM media WHERE displayName LIKE '%' || :pattern || '%' ESCAPE '\\'")
    fun observeSearchCount(pattern: String): LiveData<Int>
}
