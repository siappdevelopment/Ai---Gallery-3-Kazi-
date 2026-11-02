package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.database.PlaceScanProgress
import com.example.thirdgallery.database.PlaceSummary
import com.example.thirdgallery.repo.PlaceRepository
import com.example.thirdgallery.repo.PlaceScanManager
import java.util.Locale

/** The Places screen: every place, a search over names, the sort order and the state of the location reading. */
class PlacesViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    /** How the places are ordered. */
    enum class Sort { MOST_PHOTOS, NAME, NEWEST }

    private val repository = PlaceRepository(application)
    private val all: LiveData<List<PlaceSummary>> = repository.observePlaces()
    private val _query = MutableLiveData("")
    private val _sort = MutableLiveData(Sort.entries.getOrElse(state.get<Int>(KEY_SORT) ?: 0) { Sort.MOST_PHOTOS })

    val sort: LiveData<Sort> = _sort

    /** All places; the activity filters by name because the names depend on the language of the moment. */
    val places: LiveData<List<PlaceSummary>> = all

    val query: LiveData<String> = _query

    val scanStatus: LiveData<PlaceScanManager.Status> = PlaceScanManager.status
    val scanProgress: LiveData<PlaceScanProgress> = repository.observeProgress()

    private val _canRead = MutableLiveData(PlaceScanManager.canReadLocations(application))

    /** False while the photos' locations may not be read (Android 10+ needs the media location permission). */
    val canRead: LiveData<Boolean> = _canRead

    init {
        PlaceScanManager.start(application)
    }

    fun search(text: String) {
        if (text != _query.value) _query.value = text
    }

    fun setSort(value: Sort) {
        state[KEY_SORT] = value.ordinal
        _sort.value = value
    }

    /** Re-checks the permission (the user may have answered the request or changed it in the settings) and scans when it is there. */
    fun refreshPermission() {
        val readable = PlaceScanManager.canReadLocations(getApplication())
        if (readable && _canRead.value != true) PlaceScanManager.scanNow(getApplication())
        _canRead.value = readable
    }

    fun refresh() = PlaceScanManager.refresh(getApplication())

    fun retry() = PlaceScanManager.scanNow(getApplication())

    /** [places] filtered by [query] (by the name shown) and ordered by [sort]. */
    fun arrange(places: List<PlaceSummary>, nameOf: (PlaceSummary) -> String): List<PlaceSummary> {
        val q = _query.value.orEmpty().trim().lowercase(Locale.getDefault())
        val shown = if (q.isEmpty()) places else places.filter { nameOf(it).lowercase(Locale.getDefault()).contains(q) }
        return when (_sort.value ?: Sort.MOST_PHOTOS) {
            Sort.MOST_PHOTOS -> shown // already: most photos first
            Sort.NAME -> shown.sortedBy { nameOf(it).lowercase(Locale.getDefault()) }
            Sort.NEWEST -> shown.sortedByDescending { it.latestMs }
        }
    }

    private companion object {
        const val KEY_SORT = "places_sort"
    }
}
