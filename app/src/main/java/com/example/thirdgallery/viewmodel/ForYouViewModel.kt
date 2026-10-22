package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.example.thirdgallery.database.HighlightEntity
import com.example.thirdgallery.database.PersonEntity
import com.example.thirdgallery.database.PlaceEntity
import com.example.thirdgallery.database.SuggestionEntity
import com.example.thirdgallery.repo.ForYouRepository

class ForYouViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ForYouRepository(application)

    val highlights: LiveData<List<HighlightEntity>> = repository.highlights
    val persons: LiveData<List<PersonEntity>> = repository.persons
    val places: LiveData<List<PlaceEntity>> = repository.places
    val suggestions: LiveData<List<SuggestionEntity>> = repository.suggestions

    init {
        repository.seedIfEmpty()
    }
}
