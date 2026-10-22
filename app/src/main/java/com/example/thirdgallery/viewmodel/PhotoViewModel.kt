package com.example.thirdgallery.viewmodel

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
import com.example.thirdgallery.database.JournalDayRow
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MosaicRow
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.model.MediaUiModel
import com.example.thirdgallery.model.MosaicUi
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _filter = MutableLiveData(MediaFilter.ALL)
    val filter: LiveData<MediaFilter> = _filter

    /** Pages straight from Room, grouped by day; rebuilt when the filter changes. */
    val media: LiveData<PagingData<MediaUiModel>> = _filter.switchMap { filter ->
        repository.pagedMedia(filter)
            .map { data ->
                data.mapPaging<MediaEntity, MediaUiModel.Item> { MediaUiModel.Item(it) }
                    .insertSeparators<MediaUiModel.Item, MediaUiModel> { before, after ->
                        when {
                            after == null -> null
                            before == null || before.media.dayKey != after.media.dayKey ->
                                MediaUiModel.DayHeader(after.media.dayKey)
                            else -> null
                        }
                    }
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

    /** Number of items per day for the current filter (shown next to each day header). */
    val dayCounts: LiveData<Map<Long, Int>> = _filter.switchMap { filter ->
        repository.dayCounts(filter).map { list -> list.associate { it.dayKey to it.total } }
    }

    /** Biggest albums for the "Your library" cards, and the size of the whole library. */
    val libraryAlbums: LiveData<List<com.example.thirdgallery.database.AlbumRow>> = repository.libraryAlbums()
    val totalCount: LiveData<Int> = repository.totalCount()

    /** Timeline months (newest first) and, derived from them, the years. */
    val months: LiveData<List<com.example.thirdgallery.database.MonthRow>> = repository.months()
    val years: LiveData<List<com.example.thirdgallery.database.YearRow>> = months.map { list ->
        list.groupBy { (it.monthKey / 12).toInt() }
            .map { (year, rows) -> com.example.thirdgallery.database.YearRow(year, rows.sumOf { it.total }, rows.first().cover0) }
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
            _syncError.postValue(result == MediaRepository.SyncResult.ERROR)
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
        const val OBSERVER_DEBOUNCE_MS = 1000L
    }
}
