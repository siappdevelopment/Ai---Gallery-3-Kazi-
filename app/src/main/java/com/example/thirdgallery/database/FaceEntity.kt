package com.example.thirdgallery.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One face found in a photo (kept on the device only).
 *
 * @param mediaUid the photo ([MediaEntity.uid])
 * @param boxLeft the face's box as shares (0..1) of the upright picture's width / height
 * @param descriptor the face's compact signature (see FaceDescriptor), the basis of grouping
 * @param clusterId the person this face belongs to, null while it has not been grouped (or was too uncertain)
 */
@Entity(tableName = "faces", indices = [Index("mediaUid"), Index("clusterId")])
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUid: Long,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val descriptor: ByteArray,
    val clusterId: Long? = null,
)

/**
 * A photo whose faces were looked for; [modifiedMs] tells when it must be looked at again (it was edited).
 * A photo that could not be read has [failures] > 0 and is tried again in a later run of the app (see [FaceDao.toScan]),
 * [stamp] being the run that made the last attempt.
 */
@Entity(tableName = "face_scans")
data class FaceScanEntity(
    @PrimaryKey val mediaUid: Long,
    val modifiedMs: Long,
    @ColumnInfo(defaultValue = "0") val failures: Int = 0,
    @ColumnInfo(defaultValue = "0") val stamp: Long = 0,
)

/** The name the user gave to a person (a face group); people without a row are unnamed. */
@Entity(tableName = "face_people")
data class FacePersonEntity(
    @PrimaryKey val clusterId: Long,
    val name: String,
)

/** A grouped face with just what grouping needs. */
class ClusterFace(val clusterId: Long, val descriptor: ByteArray)

/** Photos to look at, photos looked at and faces found so far. */
data class ScanProgress(val total: Int, val scanned: Int, val faces: Int)

/** A person before their picture is looked up: just the count that decides the order. */
data class PersonCount(val clusterId: Long, val photoCount: Int)

/** The face picture of one person: the photo (by its MediaStore id and type) and the face's box. */
data class PersonFace(
    val mediaId: Long,
    val mediaType: Int,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
)

@Dao
interface FaceDao {

    /**
     * Photos (not videos) whose faces have not been looked for yet or were changed since, newest first (the recent
     * photos are the ones people expect first). A photo that could not be read is tried again, but only in a later
     * run of the app ([stamp] differs) and at most [maxFailures] times in all, so one bad file never loops. Rows from
     * before the index knew the modification time are left for the sync to fill in.
     */
    @Query(
        "SELECT m.* FROM media m LEFT JOIN face_scans s ON s.mediaUid = m.uid " +
            "WHERE m.mediaType = 1 AND m.modifiedMs > 0 AND (s.mediaUid IS NULL OR s.modifiedMs != m.modifiedMs " +
            "OR (s.failures > 0 AND s.failures < :maxFailures AND s.stamp != :stamp)) " +
            "ORDER BY m.dateMs DESC, m.uid DESC LIMIT :limit"
    )
    fun toScan(limit: Int, stamp: Long, maxFailures: Int): List<MediaEntity>

    /** How often looking at this photo failed so far (null: never recorded). */
    @Query("SELECT failures FROM face_scans WHERE mediaUid = :mediaUid AND modifiedMs = :modifiedMs")
    fun failuresOf(mediaUid: Long, modifiedMs: Long): Int?

    @Query("DELETE FROM faces WHERE mediaUid = :mediaUid")
    fun deleteFacesOf(mediaUid: Long)

    @Insert
    fun insertFaces(faces: List<FaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertScan(scan: FaceScanEntity)

    /** Stores the result of one photo: its old faces are replaced, so an edited photo never keeps stale faces. */
    @Transaction
    fun replaceFaces(scan: FaceScanEntity, faces: List<FaceEntity>) {
        deleteFacesOf(scan.mediaUid)
        if (faces.isNotEmpty()) insertFaces(faces)
        upsertScan(scan)
    }

    /** Stores the results of a whole batch of photos in one transaction (see [replaceFaces] for one photo). */
    @Transaction
    fun replaceBatch(results: List<Pair<FaceScanEntity, List<FaceEntity>>>) {
        for ((scan, faces) in results) replaceFaces(scan, faces)
    }

    /** Forgets faces, scans and names of media that no longer exists (deleted, or moved into the vault). */
    @Transaction
    fun prune() {
        pruneFaces()
        pruneScans()
        pruneNames()
    }

    @Query("DELETE FROM faces WHERE mediaUid NOT IN (SELECT uid FROM media)")
    fun pruneFaces()

    @Query("DELETE FROM face_scans WHERE mediaUid NOT IN (SELECT uid FROM media)")
    fun pruneScans()

    @Query("DELETE FROM face_people WHERE clusterId NOT IN (SELECT DISTINCT clusterId FROM faces WHERE clusterId IS NOT NULL)")
    fun pruneNames()

    /** Where the scan stands, for the progress line: eligible photos, photos already looked at, faces found. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM media WHERE mediaType = 1) AS total, " +
            "(SELECT COUNT(*) FROM media m INNER JOIN face_scans s ON s.mediaUid = m.uid " +
            "WHERE m.mediaType = 1 AND s.modifiedMs = m.modifiedMs) AS scanned, " +
            "(SELECT COUNT(*) FROM faces) AS faces"
    )
    fun observeProgress(): LiveData<ScanProgress>

    @Query("SELECT COUNT(*) FROM faces")
    fun observeFaceCount(): LiveData<Int>

    /** Changes when faces get a person (grouping) ... */
    @Query("SELECT COUNT(clusterId) FROM faces")
    fun observeGroupedCount(): LiveData<Int>

    /** ... and when people are merged, so the lists and counts are rebuilt after either. */
    @Query("SELECT COUNT(DISTINCT clusterId) FROM faces")
    fun observeClusterCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM face_people")
    fun observeNameCount(): LiveData<Int>

    @Query("SELECT id FROM faces WHERE clusterId IS NULL LIMIT 1")
    fun anyUngroupedId(): Long?

    @Query("SELECT * FROM faces WHERE clusterId IS NULL AND id > :afterId ORDER BY id LIMIT :limit")
    fun ungrouped(afterId: Long, limit: Int): List<FaceEntity>

    @Query("SELECT clusterId, descriptor FROM faces WHERE clusterId IS NOT NULL")
    fun groupedDescriptors(): List<ClusterFace>

    @Query("UPDATE faces SET clusterId = :clusterId WHERE id = :id")
    fun setCluster(id: Long, clusterId: Long)

    /** Puts every face of person [from] into person [to] and hands [from]'s name over when [to] has none. */
    @Transaction
    fun mergePeople(from: Long, to: Long) {
        moveName(from, to)
        moveFaces(from, to)
        clearName(from)
    }

    @Query("UPDATE faces SET clusterId = :to WHERE clusterId = :from")
    fun moveFaces(from: Long, to: Long)

    @Query("UPDATE face_people SET clusterId = :to WHERE clusterId = :from AND NOT EXISTS (SELECT 1 FROM face_people WHERE clusterId = :to)")
    fun moveName(from: Long, to: Long)

    // region People

    /**
     * Face groups with at least [min] different photos, the one with most photos first (ties: lowest cluster id, so
     * the order is total). Only counts are computed here; each person's picture comes from [personFace].
     */
    @Query(
        "SELECT f.clusterId AS clusterId, COUNT(DISTINCT f.mediaUid) AS photoCount " +
            "FROM faces f INNER JOIN media m ON m.uid = f.mediaUid " +
            "WHERE f.clusterId IS NOT NULL GROUP BY f.clusterId HAVING COUNT(DISTINCT f.mediaUid) >= :min " +
            "ORDER BY photoCount DESC, f.clusterId ASC LIMIT :limit"
    )
    fun peopleCounts(min: Int, limit: Int): List<PersonCount>

    /** The face used as one person's picture: the largest one (ties: the oldest face). */
    @Query(
        "SELECT fm.mediaId AS mediaId, fm.mediaType AS mediaType, ff.boxLeft AS boxLeft, ff.boxTop AS boxTop, " +
            "ff.boxRight AS boxRight, ff.boxBottom AS boxBottom " +
            "FROM faces ff INNER JOIN media fm ON fm.uid = ff.mediaUid " +
            "WHERE ff.clusterId = :clusterId " +
            "ORDER BY (ff.boxRight - ff.boxLeft) * (ff.boxBottom - ff.boxTop) DESC, ff.id ASC LIMIT 1"
    )
    fun personFace(clusterId: Long): PersonFace?

    @Query("SELECT * FROM face_people")
    fun names(): List<FacePersonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setName(person: FacePersonEntity)

    @Query("DELETE FROM face_people WHERE clusterId = :clusterId")
    fun clearName(clusterId: Long)

    // endregion
}
