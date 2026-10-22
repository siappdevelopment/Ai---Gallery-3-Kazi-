package com.example.thirdgallery.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.example.thirdgallery.model.MediaFilter

/**
 * Pages the Mosaic query straight from Room without a total-count query.
 *
 * Room's generated paging source first runs `SELECT COUNT(*)` over the whole query; the Mosaic
 * query computes each row's position inside its day with sub-queries, so that count touched every
 * row of the library (a slow query on big libraries, during which the previous filter's items
 * stayed on screen). Here only the rows of each requested page run those sub-queries.
 *
 * Keys are item offsets; [PagingSource.LoadResult.Page.prevKey] is the number of items before a page.
 */
class MosaicPagingSource(
    private val database: AppDatabase,
    private val filter: MediaFilter,
) : PagingSource<Int, MosaicRow>() {

    private val observer = object : InvalidationTracker.Observer("media") {
        override fun onInvalidated(tables: Set<String>) {
            invalidate()
        }
    }

    init {
        database.invalidationTracker.addObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override val jumpingSupported: Boolean get() = false

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MosaicRow> {
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
        if (limit <= 0) return LoadResult.Page(emptyList(), prevKey = null, nextKey = if (params is LoadParams.Prepend) key else null)

        return try {
            val rows = database.mediaDao().mosaicPage(filter.types, filter.minFavorite, limit, offset)
            LoadResult.Page(
                data = rows,
                prevKey = if (offset == 0) null else offset,
                nextKey = if (rows.size < limit) null else offset + rows.size,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, MosaicRow>): Int? {
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
