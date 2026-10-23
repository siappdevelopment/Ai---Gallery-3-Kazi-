package com.example.thirdgallery.repo

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.helper.MediaUris
import java.io.IOException

/** What happened to a batch: how many items went through and how many did not. */
class TransferResult(val done: List<Long>, val failed: Int)

/**
 * Real file operations on a device folder: Copy writes an independent copy of each file (the original
 * stays where it is, the copy keeps the date taken and the picture data), Move relocates the file with
 * the system's write consent. Items are handled one by one, so a failure never loses the others.
 * Everything here is blocking: call it off the main thread.
 */
class MediaTransferRepository(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Copying into a folder needs scoped storage (Android 10+). */
    val canCopy: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Moving needs the system write request (Android 11+). */
    val canMove: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The folder's path relative to the storage root (for example "DCIM/Camera/"), null when unknown. */
    fun folderPath(bucketId: Long): String? {
        if (!canCopy) return null
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return try {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                MediaStore.MediaColumns.BUCKET_ID + " = ?",
                arrayOf(bucketId.toString()),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        } catch (e: SecurityException) {
            null
        }
    }

    /** System confirmation to change [uids] (needed once before [move]). */
    fun writeRequest(uids: List<Long>): PendingIntent =
        MediaStore.createWriteRequest(resolver, uids.map { MediaUris.ofUid(it) })

    /** Copies every item into [path]; the originals are untouched. */
    fun copy(uids: List<Long>, path: String): TransferResult {
        val done = ArrayList<Long>(uids.size)
        var failed = 0
        for (uid in uids) {
            if (copyOne(uid, path)) done.add(uid) else failed++
        }
        return TransferResult(done, failed)
    }

    private fun copyOne(uid: Long, path: String): Boolean {
        val source = MediaUris.ofUid(uid)
        val isVideo = MediaTypes.typeOf(uid) == MediaTypes.VIDEO
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var target: Uri? = null
        return try {
            val info = resolver.query(
                source,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_TAKEN),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getLong(2)) else null } ?: return false

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, info.first)
                put(MediaStore.MediaColumns.MIME_TYPE, info.second)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                if (info.third > 0) put(MediaStore.MediaColumns.DATE_TAKEN, info.third)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = resolver.insert(collection, values) ?: return false
            target = dest
            val input = resolver.openInputStream(source) ?: throw IOException("no input")
            input.use { i ->
                (resolver.openOutputStream(dest) ?: throw IOException("no output")).use { o -> i.copyTo(o) }
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(dest, ready, null, null) > 0
        } catch (e: IOException) {
            discard(target)
            false
        } catch (e: SecurityException) {
            discard(target)
            false
        } catch (e: IllegalArgumentException) {
            // The folder is not allowed for this kind of file (for example Download for pictures).
            discard(target)
            false
        } catch (e: IllegalStateException) {
            discard(target)
            false
        }
    }

    /** Moves every item into [path] (the write request must have been accepted first). */
    fun move(uids: List<Long>, path: String): TransferResult {
        val done = ArrayList<Long>(uids.size)
        var failed = 0
        for (uid in uids) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
            val ok = try {
                resolver.update(MediaUris.ofUid(uid), values, null, null) > 0
            } catch (e: SecurityException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: IllegalStateException) {
                false
            }
            if (ok) done.add(uid) else failed++
        }
        return TransferResult(done, failed)
    }

    /** Removes the half-written copy so no broken file is left behind. */
    private fun discard(target: Uri?) {
        if (target == null) return
        try {
            resolver.delete(target, null, null)
        } catch (e: SecurityException) {
            // Nothing more to do; the pending item is cleaned up by the system.
        }
    }
}
