package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.example.thirdgallery.database.PersonEntity
import com.example.thirdgallery.database.PlaceEntity
import com.example.thirdgallery.database.SuggestionEntity
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.repo.ForYouRepository
import com.example.thirdgallery.repo.HighlightsRepository
import java.util.concurrent.Executors

class ForYouViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ForYouRepository(application)
    private val highlightsRepository = HighlightsRepository(application)

    /**
     * The Highlights row, built from the real media: rebuilt whenever the media index changes and, through
     * [refreshHighlights], when the screen comes back or the date changes (cards appear and disappear by date).
     */
    val highlights: LiveData<List<HighlightCard>> = MediatorLiveData<List<HighlightCard>>().also { result ->
        result.addSource(highlightsRepository.observeMediaCount()) { build(result) }
    }

    val persons: LiveData<List<PersonEntity>> = repository.persons
    val places: LiveData<List<PlaceEntity>> = repository.places
    val suggestions: LiveData<List<SuggestionEntity>> = repository.suggestions

    init {
        repository.seedIfEmpty()
    }

    /** Rebuilds the Highlights cards for the current moment. */
    fun refreshHighlights() {
        (highlights as? MediatorLiveData<List<HighlightCard>>)?.let { build(it) }
    }

    private fun build(target: MediatorLiveData<List<HighlightCard>>) {
        worker.execute {
            val cards = try {
                highlightsRepository.load()
            } catch (e: Exception) {
                emptyList()
            }
            target.postValue(cards)
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
