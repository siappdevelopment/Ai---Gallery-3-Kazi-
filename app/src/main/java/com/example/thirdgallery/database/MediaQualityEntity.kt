package com.example.thirdgallery.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * What was measured on one photo, once (kept on the device only): how sharp and how well exposed it is, its size,
 * and whether and when it was edited. The "Made for you" categories (Best photos, Best portraits, Recently edited)
 * are queries over this table.
 *
 * @param modifiedMs the photo's modified time when it was measured; a different value means it was edited and is measured again
 * @param version version of the measuring rules ([com.example.thirdgallery.helper.PhotoQuality.VERSION]); a new version measures again
 * @param sharpness 0..100, how crisp the picture is (focus and motion blur)
 * @param exposure 0..100, how well lit it is (not too dark, not burnt out)
 * @param score 0..100 overall quality: sharpness, exposure and resolution together
 * @param editedAtMs when the photo was edited (taken from the file), 0 when it shows no sign of an edit
 * @param failures how often measuring this photo failed; the rows with failures > 0 carry no measurements
 */
@Entity(tableName = "media_quality", indices = [Index("score"), Index("editedAtMs")])
data class MediaQualityEntity(
    @PrimaryKey val mediaUid: Long,
    val modifiedMs: Long,
    val version: Int,
    val sharpness: Int,
    val exposure: Int,
    val score: Int,
    val width: Int,
    val height: Int,
    val editedAtMs: Long,
    @ColumnInfo(defaultValue = "0") val failures: Int = 0,
    @ColumnInfo(defaultValue = "0") val stamp: Long = 0,
)

/** Photos to measure and photos measured, for the progress of the scan. */
data class QualityProgress(val total: Int, val scanned: Int)

@Dao
interface QualityDao {

    /**
     * Photos (not videos) that were not measured yet, were changed since, or were measured with older rules, newest
     * first. A photo that could not be read is tried again only in a later run of the app ([stamp] differs) and at
     * most [maxFailures] times in all, so one bad file never loops. Rows from before the index knew the modification
     * time are left for the sync to fill in.
     */
    @Query(
        "SELECT m.* FROM media m LEFT JOIN media_quality q ON q.mediaUid = m.uid " +
            "WHERE m.mediaType = 1 AND m.modifiedMs > 0 AND (q.mediaUid IS NULL OR q.modifiedMs != m.modifiedMs " +
            "OR q.version != :version OR (q.failures > 0 AND q.failures < :maxFailures AND q.stamp != :stamp)) " +
            "ORDER BY m.dateMs DESC, m.uid DESC LIMIT :limit"
    )
    fun toScan(limit: Int, stamp: Long, maxFailures: Int, version: Int): List<MediaEntity>

    /** How often measuring this photo failed so far (null: no row for this state of the photo). */
    @Query("SELECT failures FROM media_quality WHERE mediaUid = :mediaUid AND modifiedMs = :modifiedMs AND version = :version")
    fun failuresOf(mediaUid: Long, modifiedMs: Long, version: Int): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(rows: List<MediaQualityEntity>)

    /** Forgets the measurements of media that no longer exists (deleted, or moved into the vault). */
    @Query("DELETE FROM media_quality WHERE mediaUid NOT IN (SELECT uid FROM media)")
    fun prune()

    @Query(
        "SELECT (SELECT COUNT(*) FROM media WHERE mediaType = 1) AS total, " +
            "(SELECT COUNT(*) FROM media m INNER JOIN media_quality q ON q.mediaUid = m.uid " +
            "WHERE m.mediaType = 1 AND q.modifiedMs = m.modifiedMs) AS scanned"
    )
    fun observeProgress(): androidx.lifecycle.LiveData<QualityProgress>
}

/**
 * The rules of the three "Made for you" categories as SQL, so the grid, the count, the covers and the photo viewer
 * all agree. Every rule works on measured values (never on dates added or chance) and only on photos.
 */
object CategorySql {

    private const val NOT_SCREENSHOT =
        "AND m.bucketName NOT LIKE '%screenshot%' AND m.displayName NOT LIKE '%screenshot%' "

    /**
     * Best photos: crisp (sharpness >= 40), well exposed (>= 55) and of high overall quality (score >= 72; a photo the
     * user made a favorite gets 8 points more). Screenshots are never "best photos".
     */
    const val BEST =
        "FROM media m INNER JOIN media_quality q ON q.mediaUid = m.uid WHERE m.mediaType = 1 AND q.failures = 0 " +
            "AND q.sharpness >= 40 AND q.exposure >= 55 AND (q.score + m.isFavorite * 8) >= 72 " + NOT_SCREENSHOT

    /**
     * Best portraits: one to three faces (a group is not a portrait), the biggest face covers at least 4 % of the
     * picture, and the photo is clear (sharpness >= 35) and not too dark or bright (>= 45). The faces come from the
     * on-device face scan of People & faces, so a photo appears here as soon as it has been scanned.
     */
    const val PORTRAITS =
        "FROM media m INNER JOIN media_quality q ON q.mediaUid = m.uid WHERE m.mediaType = 1 AND q.failures = 0 " +
            "AND q.sharpness >= 35 AND q.exposure >= 45 " +
            "AND (SELECT COUNT(*) FROM faces f WHERE f.mediaUid = m.uid) BETWEEN 1 AND 3 " +
            "AND (SELECT MAX((f.boxRight - f.boxLeft) * (f.boxBottom - f.boxTop)) FROM faces f WHERE f.mediaUid = m.uid) >= 0.04 " +
            NOT_SCREENSHOT

    /** Recently edited: photos with a sign of an edit (see PhotoQuality) that was made since the given time (`:since`). */
    const val EDITED =
        "FROM media m INNER JOIN media_quality q ON q.mediaUid = m.uid WHERE m.mediaType = 1 AND q.failures = 0 " +
            "AND q.editedAtMs > 0 AND q.editedAtMs >= :since "
}
