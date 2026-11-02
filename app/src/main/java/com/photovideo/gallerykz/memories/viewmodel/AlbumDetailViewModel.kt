package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.MediaUiModel
import com.photovideo.gallerykz.memories.model.withDayHeaders
import com.photovideo.gallerykz.memories.repo.MediaRepository

/** One album / smart collection opened from the Albums tab: its media, paged, plus its size. */
class AlbumDetailViewModel(
    application: Application,
    state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val kind = AlbumKind.fromKey(state.get<String>(ARG_KIND))

    /** Where the photo viewer opens from, so it swipes through this album. */
    val sourceKind: AlbumKind = kind

    /** Folders and user albums only; smart collections (Favorites, Videos, ...) cannot take new media. */
    val isRegularAlbum: Boolean = kind == AlbumKind.DEVICE || kind == AlbumKind.USER
    private val id = state.get<Long>(ARG_ID) ?: 0L
    val sourceId: Long = id
    private val since = AlbumsViewModel.recentSince()

    val title: String = state.get<String>(ARG_TITLE).orEmpty()

    private val savedState = state

    private val _query = MutableLiveData(state.get<String>(KEY_QUERY).orEmpty())

    /** The file-name search typed in this album; empty shows the whole album. */
    val query: LiveData<String> = _query

    /** Whether the search field is open (kept over a rotation). */
    var searchOpen: Boolean
        get() = savedState.get<Boolean>(KEY_OPEN) ?: false
        set(value) {
            savedState[KEY_OPEN] = value
        }

    private val all: LiveData<PagingData<MediaUiModel>> = repository.pagedCollection(kind, id, since)
        .map { data -> data.withDayHeaders() }
        .cachedIn(viewModelScope)

    /** The album's media, or only those whose file name matches the search. */
    val media: LiveData<PagingData<MediaUiModel>> = _query.switchMap { q ->
        if (q.isEmpty()) {
            all
        } else {
            repository.pagedCollectionFiltered(kind, id, since, q)
                .map { data -> data.withDayHeaders() }
                .cachedIn(viewModelScope)
        }
    }

    /** The number of items shown (the matches while searching). */
    val count: LiveData<Int> = _query.switchMap { q ->
        if (q.isEmpty()) repository.collectionCount(kind, id, since) else repository.collectionCountFiltered(kind, id, since, q)
    }

    fun setQuery(text: String) {
        val trimmed = text.trim().take(MAX_QUERY)
        if (trimmed == _query.value) return
        savedState[KEY_QUERY] = trimmed
        _query.value = trimmed
    }

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
        private const val KEY_QUERY = "query"
        private const val KEY_OPEN = "search_open"
        private const val MAX_QUERY = 60
    }
}
