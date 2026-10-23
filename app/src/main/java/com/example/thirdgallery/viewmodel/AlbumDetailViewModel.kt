package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaUiModel
import com.example.thirdgallery.model.withDayHeaders
import com.example.thirdgallery.repo.MediaRepository

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

    val media: LiveData<PagingData<MediaUiModel>> = repository.pagedCollection(kind, id, since)
        .map { data -> data.withDayHeaders() }
        .cachedIn(viewModelScope)

    val count: LiveData<Int> = repository.collectionCount(kind, id, since)

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
    }
}
