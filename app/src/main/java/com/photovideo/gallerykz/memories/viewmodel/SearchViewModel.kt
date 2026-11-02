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
import androidx.paging.map as mapPaging
import com.photovideo.gallerykz.memories.database.MediaEntity
import com.photovideo.gallerykz.memories.helper.SearchHistoryPrefs
import com.photovideo.gallerykz.memories.model.MediaUiModel
import com.photovideo.gallerykz.memories.model.PersonUi
import com.photovideo.gallerykz.memories.repo.FaceScanManager
import com.photovideo.gallerykz.memories.repo.PeopleRepository
import com.photovideo.gallerykz.memories.repo.MediaRepository

/** Search by file name: the typed query, its paged results and count, and the recent searches. */
class SearchViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val peopleRepository = PeopleRepository(application)

    private val _query = MutableLiveData(state.get<String>(KEY_QUERY).orEmpty())
    val query: LiveData<String> = _query

    /** Results of the current query, newest first; nothing for an empty query. */
    val results: LiveData<PagingData<MediaUiModel>> = _query.switchMap { q ->
        if (q.isEmpty()) {
            MutableLiveData(PagingData.empty<MediaUiModel>())
        } else {
            repository.searchPaged(q)
                .map { data -> data.mapPaging<MediaEntity, MediaUiModel> { MediaUiModel.Item(it) } }
                .cachedIn(viewModelScope)
        }
    }

    val count: LiveData<Int> = _query.switchMap { q ->
        if (q.isEmpty()) MutableLiveData(0) else repository.searchCount(q)
    }

    /**
     * The People row: the same named people as on For you (see PeopleRepository.load), most photos first, at most
     * [MAX_PEOPLE]. The list follows the face scan and renaming live; no scan of its own is started here.
     */
    val people: LiveData<List<PersonUi>> = peopleRepository.observePeople().map { it.people.take(MAX_PEOPLE) }

    private val _history = MutableLiveData(SearchHistoryPrefs.get(application))
    val history: LiveData<List<String>> = _history

    init {
        // The face scan runs once per process (For you starts it as well); photos already scanned are never scanned again.
        FaceScanManager.start(application)
        // File names are indexed by the sync; make sure it has run even when the Gallery tab was never opened.
        repository.sync(false) { }
    }

    fun setQuery(text: String) {
        val trimmed = text.trim().take(MAX_QUERY)
        if (trimmed == _query.value) return
        state[KEY_QUERY] = trimmed
        _query.value = trimmed
    }

    fun renamePerson(clusterId: Long, name: String) = peopleRepository.rename(clusterId, name)

    /** Keeps a search the user submitted or opened a result of in the recent list. */
    fun remember(text: String) {
        val trimmed = text.trim().take(MAX_QUERY)
        if (trimmed.isEmpty()) return
        _history.value = SearchHistoryPrefs.add(getApplication(), trimmed)
    }

    companion object {
        const val MAX_QUERY = 60
        const val MAX_PEOPLE = 6
        private const val KEY_QUERY = "query"
    }
}
