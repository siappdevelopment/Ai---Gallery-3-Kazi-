package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.model.LanguageScreenTexts
import com.example.thirdgallery.model.LanguageUiItem
import com.example.thirdgallery.repo.LanguageRepository
import com.example.thirdgallery.utils.Event

class LanguageViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = LanguageRepository(application)

    // Nothing is selected until the user picks a language. Kept in SavedStateHandle so it
    // survives rotation and process death.
    private var selectedTag: String? = state.get<String>(KEY_TAG)?.takeIf { repository.isValidTag(it) }
    private var query: String = state.get<String>(KEY_QUERY).orEmpty()
    private var continued = false

    private val _items = MutableLiveData<List<LanguageUiItem>>()
    val items: LiveData<List<LanguageUiItem>> = _items

    private val _texts = MutableLiveData<LanguageScreenTexts>()
    val texts: LiveData<LanguageScreenTexts> = _texts

    private val _canContinue = MutableLiveData(selectedTag != null)
    val canContinue: LiveData<Boolean> = _canContinue

    private val _navigateNext = MutableLiveData<Event<String>>()
    val navigateNext: LiveData<Event<String>> = _navigateNext

    init {
        publish()
    }

    fun onLanguageSelected(tag: String) {
        if (tag == selectedTag || !repository.isValidTag(tag)) return
        selectedTag = tag
        state[KEY_TAG] = tag
        _canContinue.value = true
        publish()
    }

    fun onSearchChanged(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        state[KEY_QUERY] = trimmed
        publish()
    }

    fun onContinue() {
        val tag = selectedTag ?: return
        if (continued) return
        continued = true
        repository.saveSelection(tag)
        _navigateNext.value = Event(tag)
    }

    /**
     * Rebuilds the rows and the screen texts (no activity recreation). Only the texts follow the
     * previewed language; the language names in the list stay in their native form.
     */
    private fun publish() {
        val newTexts = repository.getScreenTexts(repository.localizedContext(selectedTag))
        if (newTexts != _texts.value) _texts.value = newTexts
        _items.value = repository.search(query).map { LanguageUiItem(it, it.tag == selectedTag) }
    }

    private companion object {
        const val KEY_TAG = "selected_tag"
        const val KEY_QUERY = "query"
    }
}
