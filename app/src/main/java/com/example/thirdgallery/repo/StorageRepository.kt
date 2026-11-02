package com.example.thirdgallery.repo

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.example.thirdgallery.helper.MediaPermissionHelper

/**
 * What fills the phone's storage. [total], [used] and [available] come from the file system; [photos] and [videos] are
 * the real file sizes of the library read from MediaStore (0 and [mediaKnown] = false when the app may not read it);
 * the rest of the used space is everything else on the phone ([other]).
 */
class StorageInfo(
    val total: Long,
    val used: Long,
    val photos: Long,
    val videos: Long,
    val mediaKnown: Boolean,
) {
    val available: Long get() = (total - used).coerceAtLeast(0L)
    val other: Long get() = (used - photos - videos).coerceAtLeast(0L)
}

class StorageRepository(context: Context) {

    private val appContext = context.applicationContext

    /** Blocking (it reads the size of every photo and video): call it off the main thread. Null when the storage cannot be read. */
    fun read(): StorageInfo? {
        val stat = try {
            StatFs(Environment.getDataDirectory().path)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val total = stat.totalBytes
        val used = total - stat.availableBytes
        val known = MediaPermissionHelper.hasAccess(appContext)
        val photos = if (known) sizeOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) else 0L
        val videos = if (known) sizeOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) else 0L
        return StorageInfo(total, used, photos, videos, known)
    }

    /** The summed size of one media table (the system does not allow SUM in a projection, so the sizes are added up here). */
    private fun sizeOf(uri: Uri): Long {
        var sum = 0L
        try {
            appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) sum += cursor.getLong(0)
            }
        } catch (e: SecurityException) {
            return 0L
        }
        return sum
    }
}
