package com.photovideo.gallerykz.memories.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.paging.Pager
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.liveData
import com.photovideo.gallerykz.memories.database.AppDatabase
import com.photovideo.gallerykz.memories.database.AlbumCardRow
import com.photovideo.gallerykz.memories.database.AlbumRow
import com.photovideo.gallerykz.memories.database.DayCount
import com.photovideo.gallerykz.memories.database.MediaDao
import com.photovideo.gallerykz.memories.database.MediaEntity
import com.photovideo.gallerykz.memories.database.MediaTypes
import com.photovideo.gallerykz.memories.database.MonthRow
import com.photovideo.gallerykz.memories.database.JournalDayRow
import com.photovideo.gallerykz.memories.database.OffsetPagingSource
import com.photovideo.gallerykz.memories.database.MosaicRow
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.helper.MediaSyncPrefs
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.GallerySort
import com.photovideo.gallerykz.memories.model.MediaFilter
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Indexes the device photos and videos into Room (MediaStore -> database, in the background)
 * and serves them to the UI as Paging 3 pages straight from the database.
 */
class MediaRepository(context: Context) {

    enum class SyncResult { SYNCED, SKIPPED, NO_ACCESS, ERROR }

    private val appContext: Context = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val dao: MediaDao = database.mediaDao()

    fun pagedMedia(filter: MediaFilter, sort: GallerySort = GallerySort.DATE_NEWEST): LiveData<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                initialLoadSize = PAGE_SIZE * 2,
                // No placeholders + a page cap keeps memory flat however big the library is.
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 6,
            ),
            pagingSourceFactory = {
                if (sort == GallerySort.DATE_NEWEST) {
                    dao.pagingSource(filter.types, filter.minFavorite)
                } else {
                    // The order comes from a fixed enum; the filter values are bound.
                    val marks = filter.types.joinToString(",") { "?" }
                    dao.filteredSource(
                        SimpleSQLiteQuery(
                            "SELECT * FROM media WHERE mediaType IN ($marks) AND isFavorite >= ? ORDER BY ${sort.orderBy}",
                            (filter.types + filter.minFavorite).toTypedArray(),
                        ),
                    )
                }
            },
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
            pagingSourceFactory = {
                OffsetPagingSource(database, "media") { limit, offset ->
                    dao.mosaicPage(filter.types, filter.minFavorite, limit, offset)
                }
            },
        ).liveData

    /** Journal: one row per day with the day's newest item (hero) and its next items (strip). */
    fun pagedJournal(filter: MediaFilter): LiveData<PagingData<JournalDayRow>> =
        Pager(
            config = PagingConfig(
                pageSize = JOURNAL_PAGE_SIZE,
                prefetchDistance = JOURNAL_PAGE_SIZE,
                initialLoadSize = JOURNAL_PAGE_SIZE * 2,
                enablePlaceholders = false,
                maxSize = JOURNAL_PAGE_SIZE * 8,
            ),
            pagingSourceFactory = {
                OffsetPagingSource(database, "media") { limit, offset ->
                    dao.journalPage(filter.types, filter.minFavorite, limit, offset)
                }
            },
        ).liveData

    /** Every item of [dayKey] under [filter]; blocking, so call it from a background thread. */
    fun uidsOfDay(dayKey: Long, filter: MediaFilter): List<Long> =
        dao.uidsOfDay(dayKey, filter.types, filter.minFavorite)

    fun dayCounts(filter: MediaFilter): LiveData<List<DayCount>> =
        dao.observeDayCounts(filter.types, filter.minFavorite)

    /** Biggest albums (folders) with their newest items, for the "Your library" cards. */
    fun libraryAlbums(): LiveData<List<AlbumRow>> = dao.observeAlbums(ALBUM_LIMIT)

    fun totalCount(): LiveData<Int> = dao.observeTotal()

    /** Months (newest first) with their five newest items: Timeline months and years. */
    fun months(): LiveData<List<MonthRow>> = dao.observeMonths()

    fun count(filter: MediaFilter): LiveData<Int> = dao.observeCount(filter.types, filter.minFavorite)

    /** Every device folder with its size and four newest items (Albums tab). */
    fun deviceAlbums(): LiveData<List<AlbumCardRow>> = dao.observeDeviceAlbums()

    /** Items of a device folder or smart collection, paged straight from Room. */
    fun pagedCollection(kind: AlbumKind, id: Long, sinceMs: Long): LiveData<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                initialLoadSize = PAGE_SIZE * 2,
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 6,
            ),
            pagingSourceFactory = { collectionSource(kind, id, sinceMs) },
        ).liveData

    fun collectionCount(kind: AlbumKind, id: Long, sinceMs: Long): LiveData<Int> = when (kind) {
        AlbumKind.USER -> dao.observeUserAlbumCount(id)
        AlbumKind.PERSON -> dao.observePersonCount(id)
        AlbumKind.PLACE -> dao.observePlaceCount(id)
        AlbumKind.BEST_PHOTOS -> dao.observeBestCount()
        AlbumKind.BEST_PORTRAITS -> dao.observePortraitCount()
        AlbumKind.RECENTLY_EDITED -> dao.observeEditedCount(CategoryRepository.editedSince())
        AlbumKind.YEAR -> dao.observeYearCount(id.toInt())
        AlbumKind.MONTH -> dao.observeMonthCount(id)
        else -> {
            val q = query(kind, id, sinceMs)
            dao.observeCollectionCount(q.bucketId, q.types, q.minFavorite, q.since, q.nameLike)
        }
    }

    private class CollectionQuery(
        val bucketId: Long,
        val types: List<Int>,
        val minFavorite: Int,
        val since: Long,
        val nameLike: String,
    )

    private fun query(kind: AlbumKind, id: Long, sinceMs: Long): CollectionQuery = when (kind) {
        AlbumKind.FAVORITES -> CollectionQuery(0, MediaFilter.FAVORITES.types, 1, 0, "")
        AlbumKind.VIDEOS -> CollectionQuery(0, MediaFilter.VIDEOS.types, 0, 0, "")
        AlbumKind.SCREENSHOTS -> CollectionQuery(0, MediaFilter.ALL.types, 0, 0, SCREENSHOT_LIKE)
        AlbumKind.RECENT -> CollectionQuery(0, MediaFilter.ALL.types, 0, sinceMs, "")
        AlbumKind.PHOTOS -> CollectionQuery(0, MediaFilter.PHOTOS.types, 0, 0, "")
        else -> CollectionQuery(id, MediaFilter.ALL.types, 0, 0, "")
    }

    private fun collectionSource(kind: AlbumKind, id: Long, sinceMs: Long) = when (kind) {
        AlbumKind.USER -> dao.userAlbumSource(id)
        AlbumKind.PERSON -> dao.personSource(id)
        AlbumKind.PLACE -> dao.placeSource(id)
        AlbumKind.BEST_PHOTOS -> dao.bestSource()
        AlbumKind.BEST_PORTRAITS -> dao.portraitSource()
        AlbumKind.RECENTLY_EDITED -> dao.editedSource(CategoryRepository.editedSince())
        AlbumKind.YEAR -> dao.yearSource(id.toInt())
        AlbumKind.MONTH -> dao.monthSource(id)
        else -> {
            val q = query(kind, id, sinceMs)
            dao.collectionSource(q.bucketId, q.types, q.minFavorite, q.since, q.nameLike)
        }
    }

    /**
     * The ordered ids behind the photo viewer, in the same order as the grid the item was tapped in.
     * [AlbumKind.DEVICE] with id 0 is the whole gallery under [filter]. Blocking: call it off the main thread.
     */
    fun viewerUids(kind: AlbumKind, id: Long, sinceMs: Long, filter: MediaFilter, searchQuery: String = ""): List<Long> = when (kind) {
        AlbumKind.SEARCH -> dao.searchUids(likePattern(searchQuery))
        AlbumKind.USER -> dao.userAlbumUids(id)
        AlbumKind.PERSON -> dao.personUids(id)
        AlbumKind.PLACE -> dao.placeUids(id)
        AlbumKind.BEST_PHOTOS -> dao.bestUids()
        AlbumKind.BEST_PORTRAITS -> dao.portraitUids()
        AlbumKind.RECENTLY_EDITED -> dao.editedUids(CategoryRepository.editedSince())
        AlbumKind.YEAR -> dao.yearUids(id.toInt())
        AlbumKind.MONTH -> dao.monthUids(id)
        AlbumKind.DEVICE -> dao.collectionUids(id, filter.types, filter.minFavorite, 0, "")
        else -> {
            val q = query(kind, id, sinceMs)
            dao.collectionUids(q.bucketId, q.types, q.minFavorite, q.since, q.nameLike)
        }
    }

    fun find(uid: Long): MediaEntity? = dao.byUid(uid)

    /** The indexed items among [uids] (at most a few hundred per call); missing ones are simply absent. */
    fun byUids(uids: List<Long>): List<MediaEntity> = dao.byUids(uids)

    /** How many photos and videos are favorites (changes when something is favorited or un-favorited). */
    fun favoriteCount(): LiveData<Int> = dao.observeCount(MediaFilter.FAVORITES.types, 1)

    /** Photos and videos whose file name contains [query], newest first. */
    fun searchPaged(query: String): LiveData<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                initialLoadSize = PAGE_SIZE * 2,
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 6,
            ),
            pagingSourceFactory = { dao.searchSource(likePattern(query)) },
        ).liveData

    fun searchCount(query: String): LiveData<Int> = dao.observeSearchCount(likePattern(query))

    /** The items of an album (a device folder, app album, smart collection, year, month, person or place) whose file name matches [query]. */
    fun pagedCollectionFiltered(kind: AlbumKind, id: Long, sinceMs: Long, query: String): LiveData<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                initialLoadSize = PAGE_SIZE * 2,
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 6,
            ),
            pagingSourceFactory = {
                val sql = filteredSql(kind, id, sinceMs, query)
                dao.filteredSource(SimpleSQLiteQuery("SELECT m.* FROM ${sql.from} WHERE ${sql.where} ORDER BY ${sql.order}", sql.args))
            },
        ).liveData

    fun collectionCountFiltered(kind: AlbumKind, id: Long, sinceMs: Long, query: String): LiveData<Int> {
        val sql = filteredSql(kind, id, sinceMs, query)
        return dao.observeFilteredCount(SimpleSQLiteQuery("SELECT COUNT(*) FROM ${sql.from} WHERE ${sql.where}", sql.args))
    }

    private class FilteredSql(val from: String, val where: String, val order: String, val args: Array<Any>)

    /** The same items and order as [collectionSource], plus "the file name contains the words of [query]". */
    private fun filteredSql(kind: AlbumKind, id: Long, sinceMs: Long, query: String): FilteredSql {
        val name = "m.displayName LIKE '%' || ? || '%' ESCAPE '\\'"
        val pattern = likePattern(query)
        return when (kind) {
            AlbumKind.USER -> FilteredSql(
                "media m INNER JOIN album_items i ON i.mediaUid = m.uid", "i.albumId = ? AND $name",
                "i.addedAt DESC, m.uid DESC", arrayOf<Any>(id, pattern),
            )
            AlbumKind.PERSON -> FilteredSql(
                "media m", "m.uid IN (SELECT mediaUid FROM faces WHERE clusterId = ?) AND $name",
                "m.dateMs DESC, m.uid DESC", arrayOf<Any>(id, pattern),
            )
            AlbumKind.PLACE -> FilteredSql(
                "media m", "m.uid IN (SELECT mediaUid FROM media_locations WHERE cell = ?) AND $name",
                "m.dateMs DESC, m.uid DESC", arrayOf<Any>(id, pattern),
            )
            AlbumKind.YEAR -> FilteredSql("media m", "(m.monthKey / 12 = ?) AND $name", "m.dateMs DESC, m.uid DESC", arrayOf<Any>(id.toInt(), pattern))
            AlbumKind.MONTH -> FilteredSql("media m", "m.monthKey = ? AND $name", "m.dateMs DESC, m.uid DESC", arrayOf<Any>(id, pattern))
            else -> {
                val q = query(kind, id, sinceMs)
                FilteredSql(
                    "media m",
                    "(? = 0 OR m.bucketId = ?) AND m.mediaType IN (${q.types.joinToString(",")}) AND m.isFavorite >= ? " +
                        "AND m.dateMs >= ? AND (? = '' OR m.bucketName LIKE ?) AND $name",
                    "m.dateMs DESC, m.uid DESC",
                    arrayOf<Any>(q.bucketId, q.bucketId, q.minFavorite, q.since, q.nameLike, q.nameLike, pattern),
                )
            }
        }
    }

    /**
     * The query as a LIKE operand: the characters % and _ (and the escape character) stay literal, and the
     * words may be separated by anything in the name ("nooedl edit" finds "Nooedl_Edit.jpg").
     */
    private fun likePattern(query: String): String =
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString("%") { it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") }

    fun setFavoriteLocal(uid: Long, favorite: Boolean) = dao.setFavorite(uid, favorite)

    fun removeLocal(uid: Long) = dao.deleteByUid(uid)

    /** True when every one of [uids] is already a favorite (an empty list is not). */
    fun allFavorites(uids: Collection<Long>): Boolean {
        if (uids.isEmpty()) return false
        var favorites = 0
        for (chunk in uids.chunked(REMOVE_CHUNK)) favorites += dao.countFavorites(chunk)
        return favorites >= uids.size
    }

    /** Marks many items as favorite (or not) in one transaction; grids and counts follow through LiveData. */
    fun setFavoritesLocal(uids: List<Long>, favorite: Boolean) {
        if (uids.isEmpty()) return
        database.runInTransaction(Runnable { uids.chunked(REMOVE_CHUNK).forEach { dao.setFavorites(it, favorite) } })
    }

    /** Drops many items from the index in one transaction (chunked below SQLite's variable limit). */
    fun removeLocalAll(uids: List<Long>) {
        if (uids.isEmpty()) return
        database.runInTransaction(Runnable { uids.chunked(REMOVE_CHUNK).forEach { dao.deleteByUids(it) } })
    }

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
        synchronized(lock) {
            // Every caller keeps its own callback, even when a sync of another screen is already running:
            // dropping it left the Gallery on its progress spinner forever.
            waiting.add(onDone)
            if (running) {
                rerun = true
                if (force) rerunForce = true
                return
            }
            running = true
        }
        runSync(force)
    }

    private fun runSync(force: Boolean) {
        val batch: List<(SyncResult) -> Unit>
        synchronized(lock) {
            batch = ArrayList(waiting)
            waiting.clear()
        }
        ioExecutor.execute {
            var result = SyncResult.ERROR
            try {
                result = syncBlocking(force)
            } catch (e: Throwable) {
                // Throwable (not only Exception): an Error must still end the sync, or the spinner never stops.
                result = SyncResult.ERROR
            }
            for (callback in batch) {
                try {
                    callback(result)
                } catch (e: Throwable) {
                    // One failing listener must not block the others.
                }
            }
            var again = false
            var againForce = false
            synchronized(lock) {
                if (rerun) {
                    again = true
                    againForce = rerunForce
                    rerun = false
                    rerunForce = false
                } else {
                    running = false
                }
            }
            if (again) runSync(againForce)
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
        var read = 0
        // One transaction: the UI sees a single change at the end instead of thousands of them.
        database.runInTransaction(Runnable {
            read += readAll(resolver, MediaTypes.IMAGE, token)
            read += readAll(resolver, MediaTypes.VIDEO, token)
            dao.deleteStale(token)
        })
        // Nothing was read: right after the permission is granted MediaStore may still answer with an empty list.
        // Remembering that as "up to date" would make every later sync skip itself and leave the Gallery empty.
        MediaSyncPrefs.setFingerprint(appContext, if (read > 0) fingerprint else null)
        return SyncResult.SYNCED
    }

    /** Streams one MediaStore table into the database in batches (never holds the whole library). */
    private fun readAll(resolver: ContentResolver, mediaType: Int, token: Long): Int {
        var total = 0
        val isVideo = mediaType == MediaTypes.VIDEO
        val uri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(DATE_TAKEN)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            if (isVideo) add(MediaStore.Video.Media.DURATION)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()

        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenCol = cursor.getColumnIndexOrThrow(DATE_TAKEN)
            val durationCol = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
            val favoriteCol = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val zone = TimeZone.getDefault()
            val calendar = java.util.Calendar.getInstance(zone)
            val batch = ArrayList<MediaEntity>(BATCH_SIZE)

            while (cursor.moveToNext()) {
                total++
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(takenCol)
                val dateMs = if (taken > 0) taken else cursor.getLong(addedCol) * 1000L
                val dayKey = Math.floorDiv(dateMs + zone.getOffset(dateMs), DAY_MS)
                val addedMs = cursor.getLong(addedCol) * 1000L
                val addedDay = Math.floorDiv(addedMs + zone.getOffset(addedMs), DAY_MS)
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
                        displayName = cursor.getString(nameCol).orEmpty(),
                        monthKey = monthKey,
                        addedMs = addedMs,
                        addedDay = addedDay,
                        modifiedMs = cursor.getLong(modifiedCol) * 1000L,
                        sizeBytes = cursor.getLong(sizeCol),
                    )
                )
                if (batch.size >= BATCH_SIZE) {
                    dao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
        }
        return total
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
        /** Shared by every repository instance: the Photos tab and For you may both ask, but only one sync runs at a time. */
        val lock = Any()
        var running = false
        var rerun = false
        var rerunForce = false
        val waiting = ArrayList<(SyncResult) -> Unit>()

        const val PAGE_SIZE = 120
        const val REMOVE_CHUNK = 500
        const val BATCH_SIZE = 1000
        const val SCREENSHOT_LIKE = "%screenshot%"
        const val JOURNAL_PAGE_SIZE = 8
        const val ALBUM_LIMIT = 12

        /** Bump when the indexed columns change so existing installs re-read MediaStore once. */
        // 8: the file size is stored too, so a library indexed before it is read again once.
        const val FINGERPRINT_VERSION = 8
        const val DAY_MS = 86_400_000L

        @Suppress("DEPRECATION")
        const val DATE_TAKEN = MediaStore.Images.Media.DATE_TAKEN

        val ioExecutor = Executors.newSingleThreadExecutor()
    }
}
