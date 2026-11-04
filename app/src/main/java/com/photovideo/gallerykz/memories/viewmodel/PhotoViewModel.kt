package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map as mapPaging
import com.photovideo.gallerykz.memories.database.JournalDayRow
import com.photovideo.gallerykz.memories.database.MediaEntity
import com.photovideo.gallerykz.memories.database.MosaicRow
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.model.GallerySort
import com.photovideo.gallerykz.memories.model.MediaFilter
import com.photovideo.gallerykz.memories.model.MediaUiModel
import com.photovideo.gallerykz.memories.model.withDayHeaders
import com.photovideo.gallerykz.memories.model.MosaicUi
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _filter = MutableLiveData(MediaFilter.ALL)
    val filter: LiveData<MediaFilter> = _filter

    private val _sort = MutableLiveData(GallerySort.DATE_NEWEST)

    /** The order of the Classic grid (date, name or size); the day-based layouts always stay by date. */
    val sort: LiveData<GallerySort> = _sort

    private val filterAndSort = androidx.lifecycle.MediatorLiveData<Pair<MediaFilter, GallerySort>>().also { result ->
        val update = { result.value = (_filter.value ?: MediaFilter.ALL) to (_sort.value ?: GallerySort.DATE_NEWEST) }
        result.addSource(_filter) { update() }
        result.addSource(_sort) { update() }
    }

    /** Pages straight from Room; rebuilt when the filter or the sort changes. Date orders are grouped by day. */
    val media: LiveData<PagingData<MediaUiModel>> = filterAndSort.switchMap { (filter, sort) ->
        repository.pagedMedia(filter, sort)
            .map { data ->
                if (sort.groupsByDay) data.withDayHeaders() else data.mapPaging<MediaEntity, MediaUiModel> { MediaUiModel.Item(it) }
            }
            .cachedIn(viewModelScope)
    }

    /**
     * Mosaic: same data with each item's place inside its day. Only collected while the Mosaic
     * layout is on screen (the fragment observes it then), so other layouts never pay for it.
     */
    val mosaicMedia: LiveData<PagingData<MosaicUi>> = _filter.switchMap { filter ->
        repository.pagedMosaic(filter)
            .map { data ->
                data.mapPaging<MosaicRow, MosaicUi.Content> { toMosaic(it) }
                    .insertSeparators<MosaicUi.Content, MosaicUi> { before, after ->
                        when {
                            after == null -> null
                            before == null || before.media.dayKey != after.media.dayKey ->
                                MosaicUi.Header(after.media.dayKey, after.media.bucketName)
                            else -> null
                        }
                    }
            }
            .cachedIn(viewModelScope)
    }

    /**
     * Journal: one row per day. Like Mosaic it is only collected while the Journal layout is shown.
     */
    val journalMedia: LiveData<PagingData<JournalDayRow>> = _filter.switchMap { filter ->
        repository.pagedJournal(filter).cachedIn(viewModelScope)
    }

    private val _shareRequest = MutableLiveData<Event<List<Long>>>()

    /** The items of the day the user wants to share (resolved off the main thread). */
    val shareRequest: LiveData<Event<List<Long>>> = _shareRequest

    /** Looks up every item of [dayKey] under the current filter and posts them for sharing. */
    fun requestShare(dayKey: Long) {
        val filter = _filter.value ?: MediaFilter.ALL
        shareExecutor.execute {
            val uids = try {
                repository.uidsOfDay(dayKey, filter)
            } catch (e: Exception) {
                emptyList()
            }
            _shareRequest.postValue(Event(uids))
        }
    }

    private val _recapRequest = MutableLiveData<Event<List<Long>>>()

    /** The media of the day to play as a Recap slideshow, oldest first (empty when the day has nothing under the filter). */
    val recapRequest: LiveData<Event<List<Long>>> = _recapRequest

    /** Looks up the day under the current filter off the main thread and posts it, in playing order, for the slideshow. */
    fun requestRecap(dayKey: Long) {
        val filter = _filter.value ?: MediaFilter.ALL
        shareExecutor.execute {
            val uids = try {
                repository.uidsOfDay(dayKey, filter).asReversed()
            } catch (e: Exception) {
                emptyList()
            }
            // A very long day plays an even selection of it, so the slideshow stays a recap and the intent stays small.
            val picked = if (uids.size > RECAP_MAX) List(RECAP_MAX) { uids[(it.toLong() * uids.size / RECAP_MAX).toInt()] } else uids
            _recapRequest.postValue(Event(picked))
        }
    }

    /** Number of items per day for the current filter (shown next to each day header). */
    val dayCounts: LiveData<Map<Long, Int>> = _filter.switchMap { filter ->
        repository.dayCounts(filter).map { list -> list.associate { it.dayKey to it.total } }
    }

    /** Biggest albums for the "Your library" cards, and the size of the whole library. */
    val libraryAlbums: LiveData<List<com.photovideo.gallerykz.memories.database.AlbumRow>> = repository.libraryAlbums()
    val totalCount: LiveData<Int> = repository.totalCount()

    /** Timeline months (newest first) and, derived from them, the years. */
    val months: LiveData<List<com.photovideo.gallerykz.memories.database.MonthRow>> = repository.months()
    val years: LiveData<List<com.photovideo.gallerykz.memories.database.YearRow>> = months.map { list ->
        list.groupBy { (it.monthKey / 12).toInt() }
            .map { (year, rows) -> com.photovideo.gallerykz.memories.database.YearRow(year, rows.sumOf { it.total }, rows.first().cover0) }
            .sortedByDescending { it.year }
    }

    /** Sizes of the Videos and Favorites chips at the top of the Timeline. */
    val videoCount: LiveData<Int> = repository.count(MediaFilter.VIDEOS)
    val favoriteCount: LiveData<Int> = repository.count(MediaFilter.FAVORITES)

    private val _syncing = MutableLiveData(false)
    val syncing: LiveData<Boolean> = _syncing

    /** True once at least one sync finished, so "empty" is never shown before the first scan. */
    private val _synced = MutableLiveData(false)
    val synced: LiveData<Boolean> = _synced

    private val _syncError = MutableLiveData(false)
    val syncError: LiveData<Boolean> = _syncError

    private val refreshRunnable = Runnable { refresh() }

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            // New or deleted media: coalesce bursts of notifications into a single sync.
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, OBSERVER_DEBOUNCE_MS)
        }
    }

    init {
        val resolver = application.contentResolver
        try {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        } catch (e: Exception) {
            // Without the observer the list still refreshes whenever the tab is shown.
        }
    }

    fun setSort(sort: GallerySort) {
        if (_sort.value != sort) _sort.value = sort
    }

    fun setFilter(filter: MediaFilter) {
        if (_filter.value != filter) _filter.value = filter
    }

    /** Syncs MediaStore into the database (a no-op when nothing changed or access is missing). */
    fun refresh(force: Boolean = false) {
        if (!MediaPermissionHelper.hasAccess(getApplication())) {
            onAccessLost()
            return
        }
        _syncing.value = true
        repository.sync(force) { result ->
            // NO_ACCESS counts as an error too: otherwise "not synced" would keep the progress spinner forever.
            _syncError.postValue(result == MediaRepository.SyncResult.ERROR || result == MediaRepository.SyncResult.NO_ACCESS)
            if (result != MediaRepository.SyncResult.NO_ACCESS) _synced.postValue(true)
            _syncing.postValue(false)
        }
    }

    /** Photo permission was revoked: drop the indexed media so nothing stale stays visible. */
    fun onAccessLost() {
        repository.clearAsync()
        _synced.value = false
        _syncing.value = false
    }

    override fun onCleared() {
        mainHandler.removeCallbacks(refreshRunnable)
        try {
            getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        } catch (e: Exception) {
            // Already gone.
        }
        super.onCleared()
    }

    /**
     * Tile rules (3 columns): rank 0 = hero block, ranks 1-2 are drawn inside it, then every 8 tiles
     * one wide tile (second of the first row) so each row stays full: 1+wide, 3, 3, 1+wide, ...
     */
    private fun toMosaic(row: MosaicRow): MosaicUi.Content = when {
        row.dayRank == 0 -> MosaicUi.Hero(row.media, row.next1, row.next2)
        row.dayRank < 3 -> MosaicUi.Spacer(row.media)
        else -> MosaicUi.Tile(row.media, wide = (row.dayRank - 3) % 8 == 1)
    }

    private companion object {
        val shareExecutor = Executors.newSingleThreadExecutor()
        private const val RECAP_MAX = 300
        const val OBSERVER_DEBOUNCE_MS = 1000L
    }
}
