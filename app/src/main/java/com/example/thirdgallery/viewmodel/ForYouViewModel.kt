package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.example.thirdgallery.database.ScanProgress
import com.example.thirdgallery.database.PlaceEntity
import com.example.thirdgallery.database.SuggestionEntity
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.PeopleResult
import com.example.thirdgallery.repo.FaceScanManager
import com.example.thirdgallery.repo.ForYouRepository
import com.example.thirdgallery.repo.HighlightsRepository
import com.example.thirdgallery.repo.PeopleRepository
import java.util.concurrent.Executors

class ForYouViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ForYouRepository(application)
    private val highlightsRepository = HighlightsRepository(application)
    private val peopleRepository = PeopleRepository(application)

    /**
     * The Highlights row, built from the real media: rebuilt whenever the media index changes and, through
     * [refreshHighlights], when the screen comes back or the date changes (cards appear and disappear by date).
     */
    val highlights: LiveData<List<HighlightCard>> = MediatorLiveData<List<HighlightCard>>().also { result ->
        result.addSource(highlightsRepository.observeMediaCount()) { build(result) }
    }

    /** The people found in the photos (see [PeopleRepository.load] for what counts as a person). */
    val people: LiveData<PeopleResult> = peopleRepository.observePeople()

    val scanStatus: LiveData<FaceScanManager.Status> = FaceScanManager.status
    val scanProgress: LiveData<ScanProgress> = peopleRepository.observeProgress()
    val places: LiveData<List<PlaceEntity>> = repository.places
    val suggestions: LiveData<List<SuggestionEntity>> = repository.suggestions

    init {
        repository.seedIfEmpty()
        // Looks for faces in the background (once per process; unchanged photos are never scanned again).
        FaceScanManager.start(application)
    }

    /** The For you screen came to the front: look for new or edited photos soon. */
    fun refreshPeople() = FaceScanManager.refresh(getApplication())

    fun retryScan() = FaceScanManager.retry(getApplication())

    fun renamePerson(clusterId: Long, name: String) = peopleRepository.rename(clusterId, name)

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
