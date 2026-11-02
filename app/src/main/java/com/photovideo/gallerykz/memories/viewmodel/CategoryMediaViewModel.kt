package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.MediaUiModel
import com.photovideo.gallerykz.memories.model.withDayHeaders
import com.photovideo.gallerykz.memories.repo.FaceScanManager
import com.photovideo.gallerykz.memories.repo.MediaQualityScanManager
import com.photovideo.gallerykz.memories.repo.MediaRepository

/**
 * One "Made for you" category (Best photos, Best portraits or Recently edited): its media, paged and grouped by
 * day, its size, and whether the library is still being checked. The same view model serves all three.
 */
class CategoryMediaViewModel(
    application: Application,
    state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    /** The category (an [AlbumKind] of the "Made for you" family); anything else falls back to Best photos. */
    val kind: AlbumKind = AlbumKind.fromKey(state.get<String>(ARG_KIND)).takeIf { it in CATEGORIES } ?: AlbumKind.BEST_PHOTOS

    @get:StringRes
    val titleRes: Int = when (kind) {
        AlbumKind.BEST_PORTRAITS -> R.string.intro4_best_portraits
        AlbumKind.RECENTLY_EDITED -> R.string.home_recently_edited
        AlbumKind.PHOTOS -> R.string.search_browse_photos
        else -> R.string.intro4_best_photos
    }

    @get:StringRes
    val subtitleRes: Int = when (kind) {
        AlbumKind.BEST_PORTRAITS -> R.string.category_portraits_subtitle
        AlbumKind.RECENTLY_EDITED -> R.string.category_edited_subtitle
        AlbumKind.PHOTOS -> R.string.category_photos_subtitle
        else -> R.string.category_best_subtitle
    }

    val media: LiveData<PagingData<MediaUiModel>> = repository.pagedCollection(kind, 0L, 0L)
        .map { data -> data.withDayHeaders() }
        .cachedIn(viewModelScope)

    val count: LiveData<Int> = repository.collectionCount(kind, 0L, 0L)

    /** True while photos (or, for portraits, faces) are still being checked, so an empty list can say so. */
    val checking: LiveData<Boolean> = MediatorLiveData<Boolean>().also { result ->
        val update = update@{
            // Photos is every photo on the device: nothing to wait for.
            if (kind == AlbumKind.PHOTOS) {
                result.value = false
                return@update
            }
            val quality = MediaQualityScanManager.status.value
            val faces = FaceScanManager.status.value
            val checkingQuality = quality == MediaQualityScanManager.Status.SCANNING || quality == MediaQualityScanManager.Status.PENDING
            val checkingFaces = kind == AlbumKind.BEST_PORTRAITS &&
                (faces == FaceScanManager.Status.SCANNING || faces == FaceScanManager.Status.PENDING)
            result.value = checkingQuality || checkingFaces
        }
        result.addSource(MediaQualityScanManager.status) { update() }
        result.addSource(FaceScanManager.status) { update() }
    }

    init {
        refresh()
    }

    /** Looks for new, edited or not yet checked photos soon; nothing is read twice. */
    fun refresh() {
        if (kind == AlbumKind.PHOTOS) return
        val app = getApplication<Application>()
        MediaQualityScanManager.refresh(app)
        if (kind == AlbumKind.BEST_PORTRAITS) FaceScanManager.refresh(app)
    }

    companion object {
        const val ARG_KIND = "kind"
        val CATEGORIES = setOf(AlbumKind.BEST_PHOTOS, AlbumKind.BEST_PORTRAITS, AlbumKind.RECENTLY_EDITED, AlbumKind.PHOTOS)
    }
}
