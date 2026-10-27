package com.example.thirdgallery.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo or video kept in the Photo vault. The file itself lives in the app's private vault folder
 * ([fileName] inside it), so it is not part of MediaStore and no gallery scan can see it.
 *
 * A row starts as [committed] = false while the original is still on the device and only becomes visible
 * once the system has deleted that original; a row that never gets committed is rolled back.
 */
@Entity(tableName = "vault_items", indices = [Index("addedAt")])
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Random name of the file inside the private vault folder. */
    val fileName: String,
    /** [MediaTypes.IMAGE] or [MediaTypes.VIDEO]. */
    val mediaType: Int,
    /** Original file name, kept for when items can be taken out of the vault again. */
    val displayName: String,
    val mimeType: String,
    val dateMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val isFavorite: Boolean,
    val addedAt: Long,
    /** [MediaEntity.uid] of the original that is being (or was) moved here. */
    val sourceUid: Long,
    val committed: Boolean,
)
