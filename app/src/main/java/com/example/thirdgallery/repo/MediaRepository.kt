package com.example.thirdgallery.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.liveData
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.AlbumRow
import com.example.thirdgallery.database.DayCount
import com.example.thirdgallery.database.MediaDao
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.database.MonthRow
import com.example.thirdgallery.database.MosaicPagingSource
import com.example.thirdgallery.database.MosaicRow
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.helper.MediaSyncPrefs
import com.example.thirdgallery.model.MediaFilter
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Indexes the device photos and videos into Room (MediaStore -> database, in the background)
 * and serves them to the UI as Paging 3 pages straight from the database.
 */
class MediaRepository(context: Context) {

    enum class SyncResult { SYNCED, SKIPPED, NO_ACCESS, ERROR }

    private val appContext: Context = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val dao: MediaDao = database.mediaDao()

    private val running = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)

    fun pagedMedia(filter: MediaFilter): LiveData<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                initialLoadSize = PAGE_SIZE * 2,
                // No placeholders + a page cap keeps memory flat however big the library is.
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 6,
            ),
            pagingSourceFactory = { dao.pagingSource(filter.types, filter.minFavorite) },
        ).liveData

    /** Same paging as [pagedMedia], with each row's position inside its day (Mosaic tile sizes). */
    fun pagedMosaic(filter: MediaFilter): LiveData<PagingData<MosaicRow>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                initialLoadSize = PAGE_SIZE * 2,
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 6,
            ),
            pagingSourceFactory = { MosaicPagingSource(database, filter) },
        ).liveData

    fun dayCounts(filter: MediaFilter): LiveData<List<DayCount>> =
        dao.observeDayCounts(filter.types, filter.minFavorite)

    /** Biggest albums (folders) with their newest items, for the "Your library" cards. */
    fun libraryAlbums(): LiveData<List<AlbumRow>> = dao.observeAlbums(ALBUM_LIMIT)

    fun totalCount(): LiveData<Int> = dao.observeTotal()

    /** Months (newest first) with their five newest items: Timeline months and years. */
    fun months(): LiveData<List<MonthRow>> = dao.observeMonths()

    fun count(filter: MediaFilter): LiveData<Int> = dao.observeCount(filter.types, filter.minFavorite)

    /** Forgets the indexed media (used when the photo permission is gone). */
    fun clearAsync() {
        ioExecutor.execute {
            try {
                dao.clear()
                MediaSyncPrefs.setFingerprint(appContext, null)
            } catch (e: Exception) {
                // Nothing else to do; the table is rebuilt on the next sync.
            }
        }
    }

    /**
     * Runs one sync on the background thread. Calls made while a sync is running are merged into
     * one follow-up run. [onDone] is called from the background thread.
     */
    fun sync(force: Boolean, onDone: (SyncResult) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true)
            return
        }
        ioExecutor.execute {
            var result = SyncResult.ERROR
            try {
                result = syncBlocking(force)
            } catch (e: Exception) {
                result = SyncResult.ERROR
            } finally {
                running.set(false)
            }
            onDone(result)
            if (rerun.getAndSet(false)) sync(false, onDone)
        }
    }

    private fun syncBlocking(force: Boolean): SyncResult {
        if (!MediaPermissionHelper.hasAccess(appContext)) return SyncResult.NO_ACCESS
        val resolver = appContext.contentResolver
        val fingerprint = fingerprint(resolver)
        if (!force && fingerprint != null && fingerprint == MediaSyncPrefs.getFingerprint(appContext)) {
            return SyncResult.SKIPPED
        }

        val token = System.nanoTime()
        // One transaction: the UI sees a single change at the end instead of thousands of them.
        database.runInTransaction(Runnable {
            readAll(resolver, MediaTypes.IMAGE, token)
            readAll(resolver, MediaTypes.VIDEO, token)
            dao.deleteStale(token)
        })
        MediaSyncPrefs.setFingerprint(appContext, fingerprint)
        return SyncResult.SYNCED
    }

    /** Streams one MediaStore table into the database in batches (never holds the whole library). */
    private fun readAll(resolver: ContentResolver, mediaType: Int, token: Long) {
        val isVideo = mediaType == MediaTypes.VIDEO
        val uri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(DATE_TAKEN)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (isVideo) add(MediaStore.Video.Media.DURATION)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()

        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenCol = cursor.getColumnIndexOrThrow(DATE_TAKEN)
            val durationCol = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
            val favoriteCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val zone = TimeZone.getDefault()
            val calendar = java.util.Calendar.getInstance(zone)
            val batch = ArrayList<MediaEntity>(BATCH_SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(takenCol)
                val dateMs = if (taken > 0) taken else cursor.getLong(addedCol) * 1000L
                val dayKey = Math.floorDiv(dateMs + zone.getOffset(dateMs), DAY_MS)
                calendar.timeInMillis = dateMs
                val monthKey = calendar.get(java.util.Calendar.YEAR) * 12L + calendar.get(java.util.Calendar.MONTH)
                batch.add(
                    MediaEntity(
                        uid = MediaTypes.uid(id, mediaType),
                        mediaId = id,
                        mediaType = mediaType,
                        dateMs = dateMs,
                        dayKey = dayKey,
                        durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                        isFavorite = favoriteCol >= 0 && cursor.getInt(favoriteCol) == 1,
                        syncToken = token,
                        bucketId = cursor.getLong(bucketIdCol),
                        bucketName = cursor.getString(bucketNameCol).orEmpty(),
                        monthKey = monthKey,
                    )
                )
                if (batch.size >= BATCH_SIZE) {
                    dao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
        }
    }

    /**
     * Cheap summary of the MediaStore state (counts, newest modification, generation). When it
     * equals the stored one nothing changed, so the sync and any UI refresh are skipped.
     */
    private fun fingerprint(resolver: ContentResolver): String? = try {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val generation = if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.getGeneration(appContext, MediaStore.VOLUME_EXTERNAL)
        } else {
            0L
        }
        "v$FINGERPRINT_VERSION|${count(resolver, images)}:${newestModified(resolver, images)}|" +
            "${count(resolver, videos)}:${newestModified(resolver, videos)}|$generation"
    } catch (e: Exception) {
        null
    }

    private fun count(resolver: ContentResolver, uri: Uri): Int =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count } ?: 0

    private fun newestModified(resolver: ContentResolver, uri: Uri): Long {
        val projection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
        val cursor = if (Build.VERSION.SDK_INT >= 26) {
            val args = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            resolver.query(uri, projection, args, null)
        } else {
            resolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT 1")
        }
        return cursor?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }

    private companion object {
        const val PAGE_SIZE = 120
        const val BATCH_SIZE = 1000
        const val ALBUM_LIMIT = 12

        /** Bump when the indexed columns change so existing installs re-read MediaStore once. */
        const val FINGERPRINT_VERSION = 4
        const val DAY_MS = 86_400_000L

        @Suppress("DEPRECATION")
        const val DATE_TAKEN = MediaStore.Images.Media.DATE_TAKEN

        val ioExecutor = Executors.newSingleThreadExecutor()
    }
}
