package com.example.thirdgallery.database

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
)

object MediaTypes {
    const val IMAGE = 1
    const val VIDEO = 3

    fun uid(mediaId: Long, mediaType: Int): Long = mediaId * 2 + if (mediaType == VIDEO) 1 else 0
}

data class DayCount(
    val dayKey: Long,
    val total: Int,
)
