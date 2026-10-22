package com.example.thirdgallery.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An album the user created in the app ("Add to album"). Device folders are not stored here. */
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** Membership of one photo/video (by [MediaEntity.uid]) in an album; one row per pair. */
@Entity(tableName = "album_items", primaryKeys = ["albumId", "mediaUid"])
data class AlbumItemEntity(
    val albumId: Long,
    val mediaUid: Long,
    val addedAt: Long,
)

/** A user album with its size and newest item (cover), for the "Add to album" list. */
data class UserAlbumRow(
    val id: Long,
    val name: String,
    val total: Int,
    val cover: Long?,
)

/** One Journal card: a day with its size, newest item (hero) and the next items (comma separated uids). */
data class JournalDayRow(
    val dayKey: Long,
    val total: Int,
    val heroUid: Long?,
    val place: String?,
    val strip: String?,
)

/** An album for the Albums tab (device folder or app album) with its four newest items (uids, newest first). */
data class AlbumCardRow(
    val id: Long,
    val name: String,
    val total: Int,
    val lastDate: Long,
    val cover0: Long?,
    val cover1: Long?,
    val cover2: Long?,
    val cover3: Long?,
)
