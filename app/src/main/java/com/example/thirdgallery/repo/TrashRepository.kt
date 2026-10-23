package com.example.thirdgallery.repo

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.helper.MediaUris

/** One photo or video in the Bin, with the date it will be deleted for good. */
data class BinItem(
    val uid: Long,
    val mediaId: Long,
    val mediaType: Int,
    /** Original capture time, so the Bin keeps the gallery's order. */
    val dateMs: Long,
    /** When the system deletes the item permanently (epoch ms), 0 when unknown. */
    val expiresMs: Long,
)

/**
 * The Bin is the system trash of MediaStore (Android 11+): moving to it, restoring and deleting for good
 * all need the user's confirmation, and a restored item returns to its original folder with its original
 * date and metadata. The list is read straight from MediaStore, so it can never drift from the real state.
 */
class TrashRepository(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** The system trash only exists on Android 11 and newer. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Every trashed photo and video, in the gallery's order. Blocking: call it off the main thread. */
    fun load(): List<BinItem> {
        if (!isSupported) return emptyList()
        val items = ArrayList<BinItem>()
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaTypes.IMAGE, items)
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaTypes.VIDEO, items)
        items.sortWith(compareByDescending<BinItem> { it.dateMs }.thenByDescending { it.uid })
        return items
    }

    private fun read(uri: Uri, mediaType: Int, out: MutableList<BinItem>) {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        resolver.query(uri, projection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val expiresCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(takenCol)
                out.add(
                    BinItem(
                        uid = MediaTypes.uid(id, mediaType),
                        mediaId = id,
                        mediaType = mediaType,
                        dateMs = if (taken > 0) taken else cursor.getLong(addedCol) * 1000L,
                        expiresMs = cursor.getLong(expiresCol) * 1000L,
                    )
                )
            }
        }
    }

    /** System confirmation to move [uids] to the Bin ([trash] = true) or take them back out. */
    fun trashRequest(uids: List<Long>, trash: Boolean): PendingIntent? =
        if (isSupported) MediaStore.createTrashRequest(resolver, uris(uids), trash) else null

    /** System confirmation to delete [uids] permanently (also for items already in the Bin). */
    fun deleteRequest(uids: List<Long>): PendingIntent? =
        if (isSupported) MediaStore.createDeleteRequest(resolver, uris(uids)) else null

    private fun uris(uids: List<Long>): List<Uri> = uids.map { MediaUris.ofUid(it) }
}
