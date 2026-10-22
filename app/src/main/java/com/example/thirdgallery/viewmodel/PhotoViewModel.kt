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
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.model.MediaUiModel
import com.example.thirdgallery.repo.MediaRepository

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

    /** Number of items per day for the current filter (shown next to each day header). */
    val dayCounts: LiveData<Map<Long, Int>> = _filter.switchMap { filter ->
        repository.dayCounts(filter).map { list -> list.associate { it.dayKey to it.total } }
    }

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

    private companion object {
        const val OBSERVER_DEBOUNCE_MS = 1000L
    }
}
