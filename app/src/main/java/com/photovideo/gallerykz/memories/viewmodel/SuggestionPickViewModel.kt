package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.model.SuggestionKind
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.repo.SuggestionsRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

/**
 * One photo suggestion with a selection (Add these to an album, Favorite these best shots): the photos it is about
 * (those that still exist and, for favorites, are still not favorites), which of them are selected (all at first),
 * and the actions of the screen: add the selection to Favorites, or dismiss the suggestion for good.
 */
class SuggestionPickViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val suggestions = SuggestionsRepository(application)

    val kind: SuggestionKind = SuggestionKind.entries.getOrNull(state.get<Int>(ARG_KIND) ?: 0)
        ?.takeIf { it != SuggestionKind.BURSTS } ?: SuggestionKind.FAVORITES
    val fromMs: Long = state.get<Long>(ARG_FROM) ?: 0L
    val toMs: Long = state.get<Long>(ARG_TO) ?: 0L

    private val _items = MutableLiveData<List<Long>>()
    val items: LiveData<List<Long>> = _items

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    /** The photos the user took out of the selection (everything else is selected). */
    private val deselected = HashSet<Long>(state.get<LongArray>(KEY_DESELECTED)?.toList().orEmpty())

    private val _selected = MutableLiveData<Set<Long>>(emptySet())
    val selected: LiveData<Set<Long>> = _selected

    private val _events = MutableLiveData<Event<PickEvent>>()
    val events: LiveData<Event<PickEvent>> = _events

    @Volatile
    private var pendingUids: List<Long> = emptyList()

    init {
        worker.execute {
            val requested = state.get<LongArray>(ARG_UIDS)?.toList().orEmpty()
            val found = requested.chunked(CHUNK).flatMap { repository.byUids(it) }.associateBy { it.uid }
            // Photos deleted since, or (for favorites) favorited since, are no longer part of the suggestion.
            val list = requested.mapNotNull { found[it] }
                .filter { kind != SuggestionKind.FAVORITES || !it.isFavorite }
                .map { it.uid }
            _items.postValue(list)
            _selected.postValue(list.filter { it !in deselected }.toSet())
            _loaded.postValue(true)
        }
    }

    fun toggle(uid: Long) {
        if (!deselected.remove(uid)) deselected.add(uid)
        publish()
    }

    /** "Deselect all" when everything is selected, otherwise "Select all". */
    fun toggleAll() {
        val all = _items.value.orEmpty()
        if (all.all { it !in deselected }) deselected.addAll(all) else deselected.clear()
        publish()
    }

    private fun publish() {
        state[KEY_DESELECTED] = deselected.toLongArray()
        _selected.value = _items.value.orEmpty().filter { it !in deselected }.toSet()
    }

    /** The selected photos, in the order of the suggestion. */
    fun selectedList(): List<Long> = _items.value.orEmpty().filter { it !in deselected }

    /** The suggestion is not wanted: it is remembered, so it does not come back. */
    fun dismiss() {
        val items = _items.value.orEmpty()
        val key = state.get<Long>(ARG_KEY) ?: 0L
        worker.execute {
            if (kind == SuggestionKind.ALBUM) suggestions.dismissAlbum(key) else suggestions.dismissFavorites(items)
            _events.postValue(Event(PickEvent.Done()))
        }
    }

    fun addToFavorites() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _events.value = Event(PickEvent.Message(R.string.viewer_unsupported))
            return
        }
        val uids = selectedList()
        if (uids.isEmpty()) return
        worker.execute {
            val resolver = getApplication<Application>().contentResolver
            val intent: PendingIntent? = try {
                MediaStore.createFavoriteRequest(resolver, uids.map { MediaUris.ofUid(it) }, true)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (intent == null) {
                _events.postValue(Event(PickEvent.Message(R.string.bin_failed)))
                return@execute
            }
            pendingUids = uids
            _events.postValue(Event(PickEvent.Launch(intent)))
        }
    }

    /** The system confirmation was answered; on "yes" the index is updated at once (every grid and count follows). */
    fun onRequestResult(accepted: Boolean) {
        val uids = pendingUids
        pendingUids = emptyList()
        if (!accepted) return
        if (uids.isEmpty()) {
            // The screen was recreated while the system dialog was open: re-read the media index.
            repository.sync(true) { }
            return
        }
        worker.execute {
            repository.setFavoritesLocal(uids, true)
            _events.postValue(Event(PickEvent.Done(R.string.selection_added_favorites, uids.size)))
        }
    }

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_UIDS = "uids"
        const val ARG_FROM = "from"
        const val ARG_TO = "to"
        const val ARG_KEY = "key"
        private const val KEY_DESELECTED = "deselected"
        private const val CHUNK = 500
        private val worker = Executors.newSingleThreadExecutor()
    }
}
