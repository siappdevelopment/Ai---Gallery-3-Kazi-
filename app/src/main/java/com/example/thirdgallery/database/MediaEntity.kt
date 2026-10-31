package com.example.thirdgallery.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
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
        Index("addedMs"),
        Index("addedDay"),
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
    /** File name with extension (MediaStore DISPLAY_NAME); the search matches against it. */
    @ColumnInfo(defaultValue = "''") val displayName: String = "",
    /** When the file was added to the device (MediaStore DATE_ADDED) in epoch milliseconds; the For you highlights use it. */
    @ColumnInfo(defaultValue = "0") val addedMs: Long = 0,
    /** Local calendar day of [addedMs], as epoch days. */
    @ColumnInfo(defaultValue = "0") val addedDay: Long = 0,
    /** When the file was last changed (MediaStore DATE_MODIFIED) in epoch milliseconds; an edited photo is scanned for faces again. */
    @ColumnInfo(defaultValue = "0") val modifiedMs: Long = 0,
)

object MediaTypes {
    const val IMAGE = 1
    const val VIDEO = 3

    fun uid(mediaId: Long, mediaType: Int): Long = mediaId * 2 + if (mediaType == VIDEO) 1 else 0

    fun mediaIdOf(uid: Long): Long = uid shr 1

    fun typeOf(uid: Long): Int = if (uid and 1L == 1L) VIDEO else IMAGE
}

/** What the For you highlights need to rank an item: nothing but light metadata. */
data class HighlightCandidate(
    val uid: Long,
    val isFavorite: Boolean,
    val dateMs: Long,
    /** Screenshots are rarely the "best" of a period, so they are left out unless nothing else is there. */
    val isShot: Boolean,
    val mediaType: Int,
    val durationMs: Long,
    /** Taken with the camera app (the "Camera" folder) rather than saved or received from elsewhere. */
    val isCamera: Boolean,
)

/** Counts of one period: everything, the favorites, and everything that is not a screenshot. */
data class PeriodStats(
    val total: Int,
    val favorites: Int,
    val plain: Int,
)

/** [PeriodStats] of one day (an epoch day of the added date). */
data class DayStats(
    val day: Long,
    val total: Int,
    val favorites: Int,
    val plain: Int,
)

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

/**
 * A media row for the Mosaic layout: its position inside its day ([dayRank], 0 = newest) and, for
 * the first item of a day only, the uids of the next two items that fill the hero block.
 */
data class MosaicRow(
    @Embedded val media: MediaEntity,
    val dayRank: Int,
    val next1: Long?,
    val next2: Long?,
)
