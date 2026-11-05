package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.photovideo.gallerykz.memories.database.ScanProgress
import com.photovideo.gallerykz.memories.helper.MemoryPrefs
import com.photovideo.gallerykz.memories.database.PlaceScanProgress
import com.photovideo.gallerykz.memories.database.PlaceSummary
import com.photovideo.gallerykz.memories.model.SuggestionCard
import com.photovideo.gallerykz.memories.model.SuggestionKind
import com.photovideo.gallerykz.memories.repo.AlbumRepository
import com.photovideo.gallerykz.memories.repo.SuggestionsRepository
import com.photovideo.gallerykz.memories.model.HighlightCard
import com.photovideo.gallerykz.memories.model.PeopleResult
import com.photovideo.gallerykz.memories.repo.CategoryRepository
import com.photovideo.gallerykz.memories.repo.FaceScanManager
import com.photovideo.gallerykz.memories.repo.MediaQualityScanManager
import com.photovideo.gallerykz.memories.repo.ForYouRepository
import com.photovideo.gallerykz.memories.repo.HighlightsRepository
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.repo.PeopleRepository
import com.photovideo.gallerykz.memories.repo.PlaceRepository
import com.photovideo.gallerykz.memories.repo.PlaceScanManager
import java.util.concurrent.Executors

class ForYouViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ForYouRepository(application)
    private val highlightsRepository = HighlightsRepository(application)
    private val peopleRepository = PeopleRepository(application)
    private val placeRepository = PlaceRepository(application)
    private val categoryRepository = CategoryRepository(application)

    // "Made for you": how many photos each category has and which photos cover its card (see CategorySql).
    val bestCount: LiveData<Int> = categoryRepository.bestCount
    val bestCovers: LiveData<List<Long>> = categoryRepository.bestCovers
    val portraitCount: LiveData<Int> = categoryRepository.portraitCount
    val portraitCovers: LiveData<List<Long>> = categoryRepository.portraitCovers
    val editedCount: LiveData<Int> = categoryRepository.editedCount
    val editedCovers: LiveData<List<Long>> = categoryRepository.editedCovers

    /** Whether the library is still being checked for the categories (so a 0 can say "checking" instead). */
    val qualityStatus: LiveData<MediaQualityScanManager.Status> = MediaQualityScanManager.status

    /**
     * The Highlights row, built from the real media: rebuilt whenever the media index changes and, through
     * [refreshHighlights], when the screen comes back or the date changes (cards appear and disappear by date).
     */
    val highlights: LiveData<List<HighlightCard>> = MediatorLiveData<List<HighlightCard>>().also { result ->
        result.addSource(highlightsRepository.observeMediaCount()) { build(result) }
        // Memory controls: "Show daily memories" or "Date-based memories" changed.
        result.addSource(MemoryPrefs.changes) { build(result) }
    }

    /** The people found in the photos (see [PeopleRepository.load] for what counts as a person). */
    val people: LiveData<PeopleResult> = peopleRepository.observePeople()

    val scanStatus: LiveData<FaceScanManager.Status> = FaceScanManager.status
    val scanProgress: LiveData<ScanProgress> = peopleRepository.observeProgress()
    /** The places found in the photos' saved locations, most photos first (see [PlaceScanManager]). */
    val places: LiveData<List<PlaceSummary>> = placeRepository.observePlaces()

    val placeStatus: LiveData<PlaceScanManager.Status> = PlaceScanManager.status
    val placeProgress: LiveData<PlaceScanProgress> = placeRepository.observeProgress()

    private val suggestionsRepository = SuggestionsRepository(application)
    private val albumRepository = AlbumRepository(application)

    /**
     * The three suggestions, worked out from the media index (see [SuggestionsRepository]) and rebuilt when the
     * media, the favorites or the albums change. A part without a suggestion is simply missing from the list.
     */
    val suggestions: LiveData<List<SuggestionCard>> = MediatorLiveData<List<SuggestionCard>>().also { result ->
        val rebuild = { _: Any? -> buildSuggestions(result) }
        result.addSource(highlightsRepository.observeMediaCount(), rebuild)
        result.addSource(MediaRepository(application).favoriteCount(), rebuild)
        result.addSource(albumRepository.albums, rebuild)
    }

    private val mediaRepository = MediaRepository(application)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { syncMedia() }

    /** New or deleted media: coalesce bursts of notifications into a single sync, like the Photos tab does. */
    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(syncRunnable)
            mainHandler.postDelayed(syncRunnable, OBSERVER_DEBOUNCE_MS)
        }
    }

    init {
        repository.seedIfEmpty()
        try {
            val resolver = application.contentResolver
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        } catch (e: Exception) {
            // Without the observer the index still refreshes whenever the screen comes to the front.
        }
        // Looks for faces in the background (once per process; unchanged photos are never scanned again).
        FaceScanManager.start(application)
        // Reads the GPS saved inside photos in the background (once per process; unchanged photos are never read again).
        PlaceScanManager.start(application)
        // Measures every photo once (quality, edits) in the background; unchanged photos are never measured again.
        MediaQualityScanManager.start(application)
        // People, Places and Highlights read the media index, which the Photos tab used to be the only one to fill.
        syncMedia()
    }

    /**
     * Brings the media index up to date from MediaStore, independent of the Photos tab. It is a no-op when nothing
     * changed or access is missing, and never runs twice at once (see [MediaRepository.sync]).
     */
    private fun syncMedia() {
        val app = getApplication<Application>()
        mediaRepository.sync(false) { result ->
            // The index changed behind the media count's back (e.g. a photo was edited): look at the locations again.
            if (result == MediaRepository.SyncResult.SYNCED) {
                PlaceScanManager.refresh(app)
                MediaQualityScanManager.refresh(app)
            }
        }
    }

    override fun onCleared() {
        mainHandler.removeCallbacks(syncRunnable)
        try {
            getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        } catch (e: Exception) {
            // Already gone.
        }
        super.onCleared()
    }

    fun refreshPlaces() {
        syncMedia()
        PlaceScanManager.refresh(getApplication())
    }

    fun retryPlaces() = PlaceScanManager.scanNow(getApplication())

    /** The For you screen came to the front: look for new or edited photos for the categories soon. */
    fun refreshCategories() = MediaQualityScanManager.refresh(getApplication())

    /** The For you screen came to the front: look for new or edited photos soon. */
    fun refreshPeople() = FaceScanManager.refresh(getApplication())

    fun retryScan() = FaceScanManager.retry(getApplication())

    fun renamePerson(clusterId: Long, name: String) = peopleRepository.rename(clusterId, name)

    /** Looks at the suggestions again (the For you screen came back, or one was dismissed). */
    fun refreshSuggestions() {
        (suggestions as? MediatorLiveData<List<SuggestionCard>>)?.let { buildSuggestions(it) }
    }

    private fun buildSuggestions(target: MediatorLiveData<List<SuggestionCard>>) {
        worker.execute {
            val cards = try {
                val set = suggestionsRepository.load()
                buildList {
                    set.album?.let { add(SuggestionCard(SuggestionKind.ALBUM, it.uids.size, it.uids.take(3), it.uids, it.fromMs, it.toMs)) }
                    if (set.bursts.isNotEmpty()) {
                        add(SuggestionCard(SuggestionKind.BURSTS, set.bursts.size, set.bursts.take(3).map { b -> b.bestUid }, emptyList(), 0L, 0L))
                    }
                    set.favorites?.let { add(SuggestionCard(SuggestionKind.FAVORITES, it.uids.size, it.uids.take(3), it.uids, 0L, 0L)) }
                }
            } catch (e: Exception) {
                emptyList()
            }
            target.postValue(cards)
        }
    }

    /** Rebuilds the Highlights cards for the current moment. */
    fun refreshHighlights() {
        (highlights as? MediatorLiveData<List<HighlightCard>>)?.let { build(it) }
    }

    private fun build(target: MediatorLiveData<List<HighlightCard>>) {
        worker.execute {
            val cards = try {
                // "Show daily memories" off: no Highlights are built at all.
                if (MemoryPrefs.isDailyMemories(getApplication())) highlightsRepository.load() else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            target.postValue(cards)
        }
    }

    private companion object {
        const val OBSERVER_DEBOUNCE_MS = 1000L
        val worker = Executors.newSingleThreadExecutor()
    }
}
