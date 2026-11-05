package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.photovideo.gallerykz.memories.database.MediaEntity
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.MediaFilter
import com.photovideo.gallerykz.memories.repo.AlbumRepository
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

/**
 * "Add photos" to an existing app album: the gallery to choose from, the selection and the add itself. Nothing is copied or
 * moved on the device; the photos only become members of the album (the same as "Add to album" from the Gallery).
 */
class AlbumPickViewModel(application: Application, state: SavedStateHandle) : AndroidViewModel(application) {

    private val media = MediaRepository(application)
    private val albums = AlbumRepository(application)

    val albumId: Long = state.get<Long>(ARG_ID) ?: 0L
    val albumTitle: String = state.get<String>(ARG_TITLE).orEmpty()

    val items: LiveData<PagingData<MediaEntity>> =
        media.pagedCollection(AlbumKind.DEVICE, 0L, 0L).cachedIn(viewModelScope)

    /** Photos and videos in the gallery (what "Select all" selects). */
    val total: LiveData<Int> = media.collectionCount(AlbumKind.DEVICE, 0L, 0L)

    private val selection = LinkedHashSet<Long>()

    private val _count = MutableLiveData(0)
    val count: LiveData<Int> = _count

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    /** The number of photos added, or -1 when adding failed. */
    private val _result = MutableLiveData<Event<Int>>()
    val result: LiveData<Event<Int>> = _result

    fun isSelected(uid: Long) = synchronized(selection) { selection.contains(uid) }

    fun toggle(uid: Long) {
        if (_busy.value == true) return
        synchronized(selection) { if (!selection.remove(uid)) selection.add(uid) }
        _count.value = synchronized(selection) { selection.size }
    }

    /** Select all, or clear when everything is already chosen. */
    fun toggleAll() {
        if (_busy.value == true) return
        val chosen = synchronized(selection) { selection.size }
        if (chosen > 0 && chosen >= (total.value ?: 0)) {
            synchronized(selection) { selection.clear() }
            _count.value = 0
            return
        }
        worker.execute {
            val all = media.viewerUids(AlbumKind.DEVICE, 0L, 0L, MediaFilter.ALL)
            val size = synchronized(selection) { selection.addAll(all); selection.size }
            _count.postValue(size)
        }
    }

    fun add() {
        val uids = synchronized(selection) { selection.toList() }
        if (uids.isEmpty() || albumId <= 0L || _busy.value == true) return
        _busy.value = true
        albums.addItems(albumId, uids) { ok ->
            _busy.postValue(false)
            _result.postValue(Event(if (ok) uids.size else -1))
        }
    }

    companion object {
        const val ARG_ID = "album_id"
        const val ARG_TITLE = "album_title"
        private val worker = Executors.newSingleThreadExecutor()
    }
}
