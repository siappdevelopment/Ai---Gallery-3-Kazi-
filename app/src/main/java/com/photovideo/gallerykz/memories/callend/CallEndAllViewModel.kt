package com.photovideo.gallerykz.memories.callend

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

/** The "All" tab: the device's photos or its videos (switch on top), newest first, grouped by day. */
class CallEndAllViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _videos = MutableLiveData(state.get<Boolean>(KEY_VIDEOS) ?: false)

    /** True while the Videos chip is chosen. */
    val showVideos: LiveData<Boolean> = _videos

    private val photos: LiveData<PagingData<MediaUiModel>> = repository.pagedCollection(AlbumKind.PHOTOS, 0L, 0L)
        .map { it.withDayHeaders() }
        .cachedIn(viewModelScope)

    private val videos: LiveData<PagingData<MediaUiModel>> = repository.pagedCollection(AlbumKind.VIDEOS, 0L, 0L)
        .map { it.withDayHeaders() }
        .cachedIn(viewModelScope)

    val media: LiveData<PagingData<MediaUiModel>> = _videos.switchMap { if (it) videos else photos }

    /** The collection the viewer opens in, so it swipes through what the tab shows. */
    val kind: AlbumKind get() = if (_videos.value == true) AlbumKind.VIDEOS else AlbumKind.PHOTOS

    fun selectVideos(videos: Boolean) {
        if (_videos.value == videos) return
        state[KEY_VIDEOS] = videos
        _videos.value = videos
    }

    private companion object {
        const val KEY_VIDEOS = "videos"
    }
}
