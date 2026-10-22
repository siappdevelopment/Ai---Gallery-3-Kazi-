package com.example.thirdgallery.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase

/**
 * Pages any "LIMIT/OFFSET" query straight from Room without a total-count query.
 *
 * Room's generated paging source first runs `SELECT COUNT(*)` over the whole query. Queries with
 * per-row sub-queries (Mosaic ranks, Journal days) made that count touch the whole library, which
 * is slow on big libraries. Here only the requested page runs. Keys are item offsets and
 * [PagingSource.LoadResult.Page.prevKey] is the number of items before the page. The source is
 * invalidated whenever one of [tables] changes.
 */
class OffsetPagingSource<T : Any>(
    private val database: RoomDatabase,
    vararg tables: String,
    private val loader: suspend (limit: Int, offset: Int) -> List<T>,
) : PagingSource<Int, T>() {

    private val observer = object : InvalidationTracker.Observer(arrayOf(*tables)) {
        override fun onInvalidated(tables: Set<String>) {
            invalidate()
        }
    }

    init {
        database.invalidationTracker.addObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override val jumpingSupported: Boolean get() = false

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val key = (params.key ?: 0).coerceAtLeast(0)
        val limit: Int
        val offset: Int
        if (params is LoadParams.Prepend) {
            // The key is how many items exist before the page being shown.
            limit = minOf(params.loadSize, key)
            offset = key - limit
        } else {
            limit = params.loadSize
            offset = key
        }
        if (limit <= 0) {
            return LoadResult.Page(emptyList(), prevKey = null, nextKey = if (params is LoadParams.Prepend) key else null)
        }
        return try {
            val rows = loader(limit, offset)
            LoadResult.Page(
                data = rows,
                prevKey = if (offset == 0) null else offset,
                nextKey = if (rows.size < limit) null else offset + rows.size,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        var start = 0
        for (page in state.pages) {
            if (anchor < start + page.data.size) {
                val offsetOfPage = page.prevKey ?: 0
                return (offsetOfPage + (anchor - start) - state.config.initialLoadSize / 2).coerceAtLeast(0)
            }
            start += page.data.size
        }
        return null
    }
}
