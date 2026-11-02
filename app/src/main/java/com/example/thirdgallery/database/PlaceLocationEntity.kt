package com.example.thirdgallery.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The GPS position saved inside one photo (read once from its metadata, kept on the device only).
 *
 * @param mediaUid the photo ([MediaEntity.uid])
 * @param modifiedMs the photo's modified time when it was read; a different value means it was edited and is read again
 * @param cell the place the photo belongs to (see [PlaceCells]); null when the photo has no usable location
 */
@Entity(tableName = "media_locations", indices = [Index("cell")])
data class MediaLocationEntity(
    @PrimaryKey val mediaUid: Long,
    val modifiedMs: Long,
    val cell: Long?,
    val latitude: Double,
    val longitude: Double,
)

/** The readable name found for a place (kept so a place is looked up once); empty when the position has no name. */
@Entity(tableName = "place_names")
data class PlaceNameEntity(
    @PrimaryKey val cell: Long,
    val name: String,
)

/**
 * One place of the Places list: photos whose positions fall in the same 0.1 degree square (about 11 km).
 * [coverUid] is the newest photo of the place, [latestMs] when it was taken.
 */
data class PlaceSummary(
    val cell: Long,
    val itemCount: Int,
    val latitude: Double,
    val longitude: Double,
    val placeName: String?,
    val latestMs: Long,
    val coverUid: Long?,
)

/** Photos to look at and photos looked at, for the scan progress line. */
data class PlaceScanProgress(val total: Int, val scanned: Int)

/** Turning a position into the id of its place. */
object PlaceCells {
    private const val STEP = 10.0 // squares per degree
    private const val COLUMNS = 4000L

    /** Null for a position that cannot be real, or the "0, 0" a missing value often turns into. */
    fun of(latitude: Double, longitude: Double): Long? {
        if (latitude.isNaN() || longitude.isNaN() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        val row = Math.floor(latitude * STEP).toLong() + 900L
        val column = Math.floor(longitude * STEP).toLong() + 1800L
        return row * COLUMNS + column
    }
}

@Dao
interface LocationDao {

    /** Photos (not videos) whose location was not read yet or was changed since, newest first. */
    @Query(
        "SELECT m.* FROM media m LEFT JOIN media_locations l ON l.mediaUid = m.uid " +
            "WHERE m.mediaType = 1 AND m.modifiedMs > 0 AND (l.mediaUid IS NULL OR l.modifiedMs != m.modifiedMs) " +
            "ORDER BY m.dateMs DESC, m.uid DESC LIMIT :limit"
    )
    fun toScan(limit: Int): List<MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(locations: List<MediaLocationEntity>)

    /** Forgets locations of media that no longer exists (deleted, or moved into the vault). */
    @Query("DELETE FROM media_locations WHERE mediaUid NOT IN (SELECT uid FROM media)")
    fun prune()

    /** The places, the one with most photos first, each with its newest photo as the picture. */
    @Query(
        "SELECT l.cell AS cell, COUNT(*) AS itemCount, AVG(l.latitude) AS latitude, AVG(l.longitude) AS longitude, " +
            "n.name AS placeName, MAX(m.dateMs) AS latestMs, " +
            "(SELECT m2.uid FROM media_locations l2 INNER JOIN media m2 ON m2.uid = l2.mediaUid " +
            "WHERE l2.cell = l.cell ORDER BY m2.dateMs DESC, m2.uid DESC LIMIT 1) AS coverUid " +
            "FROM media_locations l INNER JOIN media m ON m.uid = l.mediaUid LEFT JOIN place_names n ON n.cell = l.cell " +
            "WHERE l.cell IS NOT NULL GROUP BY l.cell ORDER BY itemCount DESC, l.cell ASC"
    )
    fun places(): List<PlaceSummary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertName(name: PlaceNameEntity)

    // The screens rebuild their lists when any of these change.

    @Query("SELECT COUNT(*) FROM media_locations")
    fun observeRowCount(): LiveData<Int>

    @Query("SELECT COUNT(cell) FROM media_locations")
    fun observeLocatedCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM place_names")
    fun observeNameCount(): LiveData<Int>

    @Query(
        "SELECT (SELECT COUNT(*) FROM media WHERE mediaType = 1) AS total, " +
            "(SELECT COUNT(*) FROM media m INNER JOIN media_locations l ON l.mediaUid = m.uid " +
            "WHERE m.mediaType = 1 AND l.modifiedMs = m.modifiedMs) AS scanned"
    )
    fun observeProgress(): LiveData<PlaceScanProgress>
}
