package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.database.UserAlbumRow
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.AlbumRepository
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

/**
 * Backs the "Add to album" sheet for one Journal day: the items to add are every photo and video
 * of that day under the filter that was active when the sheet opened.
 */
class AddToAlbumViewModel(
    application: Application,
    state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val albumRepository = AlbumRepository(application)
    private val mediaRepository = MediaRepository(application)

    private val dayKey: Long = state.get<Long>(ARG_DAY_KEY) ?: 0L
    private val filter: MediaFilter =
        MediaFilter.entries.getOrNull(state.get<Int>(ARG_FILTER) ?: 0) ?: MediaFilter.ALL

    /** Opened from the Albums tab "New" button: only creates an empty album, no items to add. */
    val isCreateOnly: Boolean = dayKey == CREATE_ONLY

    @Volatile
    private var uids: List<Long> = emptyList()

    @Volatile
    private var busy = false

    val albums: LiveData<List<UserAlbumRow>> = albumRepository.albums

    private val _itemCount = MutableLiveData(state.get<Int>(ARG_COUNT) ?: 0)
    val itemCount: LiveData<Int> = _itemCount

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    private val _result = MutableLiveData<Event<AlbumRepository.Result>>()
    val result: LiveData<Event<AlbumRepository.Result>> = _result

    init {
        if (isCreateOnly) {
            _itemCount.value = 0
            _loaded.value = true
        } else loader.execute {
            try {
                val list = mediaRepository.uidsOfDay(dayKey, filter)
                uids = list
                _itemCount.postValue(list.size)
            } catch (e: Exception) {
                uids = emptyList()
            }
            _loaded.postValue(true)
        }
    }

    fun addTo(album: UserAlbumRow) {
        if (!canStart()) return
        albumRepository.addToAlbum(album, uids, ::finish)
    }

    fun createAndAdd(name: String) {
        if (!canStart()) return
        albumRepository.createAlbum(name, uids, ::finish)
    }

    /** Ignores taps while a previous one is still running or the items are not loaded yet. */
    private fun canStart(): Boolean {
        if (busy || _loaded.value != true) return false
        busy = true
        return true
    }

    private fun finish(result: AlbumRepository.Result) {
        busy = false
        _result.postValue(Event(result))
    }

    companion object {
        const val ARG_DAY_KEY = "dayKey"
        const val ARG_FILTER = "filter"
        const val ARG_COUNT = "count"
        const val CREATE_ONLY = -1L
        private val loader = Executors.newSingleThreadExecutor()
    }
}
