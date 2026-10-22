package com.example.thirdgallery.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One device photo or video indexed from MediaStore. Only light metadata is stored; the picture
 * itself is always loaded from the content URI built from [mediaId] and [mediaType].
 */
@Entity(
    tableName = "media",
    indices = [
        Index("dateMs"),
        Index("mediaType", "dateMs"),
        Index("dayKey"),
        Index("bucketId", "dateMs"),
        Index("monthKey", "dateMs"),
    ],
)
data class MediaEntity(
    /** Unique across photos and videos (their MediaStore ids can collide): id * 2 + (video ? 1 : 0). */
    @PrimaryKey val uid: Long,
    val mediaId: Long,
    /** [MediaTypes.IMAGE] or [MediaTypes.VIDEO]. */
    val mediaType: Int,
    /** Capture time (or added time when unknown) in epoch milliseconds. */
    val dateMs: Long,
    /** Local calendar day of [dateMs], as epoch days; groups the grid by day. */
    val dayKey: Long,
    val durationMs: Long,
    val isFavorite: Boolean,
    /** Id of the sync run that last saw this row; rows from older runs were deleted on the device. */
    val syncToken: Long,
    /** MediaStore album (folder) of the item; used for the "Your library" cards. */
    @ColumnInfo(defaultValue = "0") val bucketId: Long = 0,
    @ColumnInfo(defaultValue = "''") val bucketName: String = "",
    /** Local year * 12 + month (0-based) of [dateMs]; groups the Timeline month and year views. */
    @ColumnInfo(defaultValue = "0") val monthKey: Long = 0,
)

object MediaTypes {
    const val IMAGE = 1
    const val VIDEO = 3

    fun uid(mediaId: Long, mediaType: Int): Long = mediaId * 2 + if (mediaType == VIDEO) 1 else 0

    fun mediaIdOf(uid: Long): Long = uid shr 1

    fun typeOf(uid: Long): Int = if (uid and 1L == 1L) VIDEO else IMAGE
}

data class DayCount(
    val dayKey: Long,
    val total: Int,
)

/** One "Your library" card: an album with its size and its three newest items (uids, newest first). */
data class AlbumRow(
    val bucketId: Long,
    val bucketName: String,
    val total: Int,
    val cover0: Long?,
    val cover1: Long?,
    val cover2: Long?,
)

/** One Timeline month card: size and its five newest items (uids, newest first). */
data class MonthRow(
    val monthKey: Long,
    val total: Int,
    val cover0: Long?,
    val cover1: Long?,
    val cover2: Long?,
    val cover3: Long?,
    val cover4: Long?,
)

/** One Timeline year card, computed from the month rows. */
data class YearRow(
    val year: Int,
    val total: Int,
    val cover: Long?,
)
