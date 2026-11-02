package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.example.thirdgallery.database.ScanProgress
import com.example.thirdgallery.database.PlaceScanProgress
import com.example.thirdgallery.database.PlaceSummary
import com.example.thirdgallery.database.SuggestionEntity
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.PeopleResult
import com.example.thirdgallery.repo.FaceScanManager
import com.example.thirdgallery.repo.ForYouRepository
import com.example.thirdgallery.repo.HighlightsRepository
import com.example.thirdgallery.repo.PeopleRepository
import com.example.thirdgallery.repo.PlaceRepository
import com.example.thirdgallery.repo.PlaceScanManager
import java.util.concurrent.Executors

class ForYouViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ForYouRepository(application)
    private val highlightsRepository = HighlightsRepository(application)
    private val peopleRepository = PeopleRepository(application)
    private val placeRepository = PlaceRepository(application)

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
    /** The places found in the photos' saved locations, most photos first (see [PlaceScanManager]). */
    val places: LiveData<List<PlaceSummary>> = placeRepository.observePlaces()

    val placeStatus: LiveData<PlaceScanManager.Status> = PlaceScanManager.status
    val placeProgress: LiveData<PlaceScanProgress> = placeRepository.observeProgress()
    val suggestions: LiveData<List<SuggestionEntity>> = repository.suggestions

    init {
        repository.seedIfEmpty()
        // Looks for faces in the background (once per process; unchanged photos are never scanned again).
        FaceScanManager.start(application)
        // Reads the GPS saved inside photos in the background (once per process; unchanged photos are never read again).
        PlaceScanManager.start(application)
    }

    fun refreshPlaces() = PlaceScanManager.refresh(getApplication())

    fun retryPlaces() = PlaceScanManager.scanNow(getApplication())

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
