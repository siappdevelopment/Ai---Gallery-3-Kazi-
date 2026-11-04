package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.MediaFilter
import com.photovideo.gallerykz.memories.repo.AlbumRepository
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

/** Something the screen has to show or launch for the selection (system confirmations, toasts). */
sealed class SelectionEvent {
    class Launch(val intent: PendingIntent) : SelectionEvent()
    class Message(@StringRes val res: Int, val count: Int = 0) : SelectionEvent()
}

/**
 * Multi-selection of photos and videos for one screen (Gallery tab or an album): the chosen ids,
 * whether selection mode is on, and the actions that need the system (Favorite, Move to Bin).
 * It lives in the activity, so the "Add to album" sheet shares the same selection.
 */
class SelectionViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private enum class Pending { FAVORITE, UNFAVORITE, TRASH }

    private val repository = MediaRepository(application)

    private val _active = MutableLiveData(state.get<Boolean>(KEY_ACTIVE) ?: false)
    val active: LiveData<Boolean> = _active

    private val _selected = MutableLiveData<Set<Long>>(state.get<LongArray>(KEY_IDS)?.toHashSet() ?: emptySet())
    val selected: LiveData<Set<Long>> = _selected

    private val _events = MutableLiveData<Event<SelectionEvent>>()
    val events: LiveData<Event<SelectionEvent>> = _events

    /** Where "Select all" reads its ids from: the same list the grid shows. */
    @Volatile
    private var sourceKind = AlbumKind.DEVICE

    @Volatile
    private var sourceId = 0L

    @Volatile
    private var sourceFilter = MediaFilter.ALL

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var pendingUids: List<Long> = emptyList()

    /** Chunks still to confirm (only touched on the worker thread) and how many items are already done. */
    private val queue = ArrayDeque<List<Long>>()

    @Volatile
    private var processed = 0

    val isActive: Boolean get() = _active.value == true

    /** Selecting inside the Favorite filter / collection: the Favorite action becomes Unfavorite. */
    val isUnfavoriteMode: Boolean
        get() = sourceFilter == MediaFilter.FAVORITES || sourceKind == AlbumKind.FAVORITES || _allFavorite.value == true

    /** True while every selected item is already a favorite: the Favorite action then un-favorites them. */
    private val _allFavorite = MutableLiveData(false)
    val allFavorite: LiveData<Boolean> = _allFavorite

    @Volatile
    private var favoriteCheck = 0

    init {
        // A selection restored after process death needs its state too.
        refreshFavoriteState(_selected.value.orEmpty())
    }

    /** Looks the selection up in the index off the main thread (a long selection must not block taps). */
    private fun refreshFavoriteState(set: Set<Long>) {
        val check = ++favoriteCheck
        if (set.isEmpty()) {
            _allFavorite.value = false
            return
        }
        val ids = set.toList()
        worker.execute {
            val all = try {
                repository.allFavorites(ids)
            } catch (e: Exception) {
                false
            }
            // Only the newest answer counts; the selection may have changed while this one was running.
            if (check == favoriteCheck) _allFavorite.postValue(all)
        }
    }

    fun isSelected(uid: Long): Boolean = _selected.value?.contains(uid) == true

    fun count(): Int = _selected.value?.size ?: 0

    fun snapshot(): List<Long> = _selected.value.orEmpty().sortedDescending()

    fun setSource(kind: AlbumKind, id: Long, filter: MediaFilter) {
        sourceKind = kind
        sourceId = id
        sourceFilter = filter
    }

    /** Selection mode on with nothing chosen yet (the "select" button of an album). */
    fun begin() {
        if (!isActive) setActive(true)
    }

    fun enter(uid: Long) {
        setActive(true)
        publish(_selected.value.orEmpty() + uid)
    }

    fun toggle(uid: Long) {
        val current = _selected.value.orEmpty()
        val next = if (uid in current) current - uid else current + uid
        // Deselecting the last item ends selection mode (the normal bars come back at once).
        if (next.isEmpty()) clear() else publish(next)
    }

    fun clear() {
        setActive(false)
        publish(emptySet())
    }

    fun selectAll() {
        worker.execute {
            val all = try {
                repository.viewerUids(sourceKind, sourceId, AlbumsViewModel.recentSince(), sourceFilter).toHashSet()
            } catch (e: SecurityException) {
                emptySet()
            }
            mainHandler.post { publish(all) }
        }
    }

    fun favorite() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _events.value = Event(SelectionEvent.Message(R.string.viewer_unsupported))
            return
        }
        request(if (isUnfavoriteMode) Pending.UNFAVORITE else Pending.FAVORITE)
    }

    fun moveToBin() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _events.value = Event(SelectionEvent.Message(R.string.viewer_unsupported))
            return
        }
        request(Pending.TRASH)
    }

    /** Delete inside an app album: the selected items leave that album only; the files and other albums are untouched. */
    fun removeFromAlbum(albumId: Long) {
        val uids = snapshot()
        if (uids.isEmpty() || albumId <= 0L) return
        worker.execute {
            val message = try {
                AlbumRepository(getApplication()).removeItems(albumId, uids)
                SelectionEvent.Message(R.string.selection_removed_album, uids.size)
            } catch (e: Exception) {
                SelectionEvent.Message(R.string.bin_failed)
            }
            _events.postValue(Event(message))
            mainHandler.post { clear() }
        }
    }

    private fun request(action: Pending) {
        val uids = snapshot()
        if (uids.isEmpty()) return
        worker.execute {
            // One system request cannot carry thousands of items (it failed with 2,000+ photos):
            // the selection is sent in chunks, one system confirmation per chunk.
            queue.clear()
            queue.addAll(uids.chunked(CHUNK))
            processed = 0
            pending = action
            launchNext(action)
        }
    }

    /** Builds the system request for the first chunk of the queue (worker thread); a chunk that is refused is split in two. */
    private fun launchNext(action: Pending) {
        val resolver = getApplication<Application>().contentResolver
        while (true) {
            val chunk = queue.firstOrNull() ?: return finishRequests(action)
            val uris = chunk.map { MediaUris.ofUid(it) }
            val intent = try {
                when (action) {
                    Pending.FAVORITE -> MediaStore.createFavoriteRequest(resolver, uris, true)
                    Pending.UNFAVORITE -> MediaStore.createFavoriteRequest(resolver, uris, false)
                    Pending.TRASH -> MediaStore.createTrashRequest(resolver, uris, true)
                }
            } catch (e: Exception) {
                null
            }
            if (intent != null) {
                pendingUids = chunk
                _events.postValue(Event(SelectionEvent.Launch(intent)))
                return
            }
            if (chunk.size > MIN_CHUNK) {
                val half = chunk.size / 2
                queue.removeFirst()
                queue.addFirst(chunk.subList(half, chunk.size))
                queue.addFirst(chunk.subList(0, half))
                continue
            }
            // Even a small chunk is refused: stop here and report what was done so far.
            queue.clear()
            pending = null
            pendingUids = emptyList()
            if (processed > 0) finishRequests(action) else {
                _events.postValue(Event(SelectionEvent.Message(R.string.bin_failed)))
            }
            return
        }
    }

    private fun finishRequests(action: Pending) {
        val done = processed
        queue.clear()
        pending = null
        pendingUids = emptyList()
        processed = 0
        if (done > 0) {
            val message = when (action) {
                Pending.FAVORITE -> R.string.selection_added_favorites
                Pending.UNFAVORITE -> R.string.selection_removed_favorites
                Pending.TRASH -> R.string.selection_moved_bin
            }
            _events.postValue(Event(SelectionEvent.Message(message, done)))
        }
        mainHandler.post { clear() }
    }

    /** The system confirmation was answered; on "yes" the index is updated at once (grids and counts follow). */
    fun onRequestResult(accepted: Boolean) {
        val action = pending
        val uids = pendingUids
        if (action == null) {
            pendingUids = emptyList()
            // The screen was recreated while the system dialog was open: re-read the media index.
            if (accepted) repository.sync(true) { }
            return
        }
        worker.execute {
            if (!accepted) {
                // Cancelled: what earlier chunks already did stays done; the rest is dropped.
                finishRequests(action)
                return@execute
            }
            when (action) {
                Pending.FAVORITE -> repository.setFavoritesLocal(uids, true)
                Pending.UNFAVORITE -> repository.setFavoritesLocal(uids, false)
                Pending.TRASH -> repository.removeLocalAll(uids)
            }
            processed += uids.size
            queue.removeFirstOrNull()
            pendingUids = emptyList()
            launchNext(action)
        }
    }

    private fun setActive(on: Boolean) {
        _active.value = on
        state[KEY_ACTIVE] = on
    }

    private fun publish(set: Set<Long>) {
        _selected.value = set
        persist(set)
        refreshFavoriteState(set)
    }

    /** Small selections survive process death; very large ones are not saved (saved state has a size limit). */
    private fun persist(set: Set<Long>) {
        state[KEY_IDS] = if (set.size <= MAX_SAVED) set.toLongArray() else LongArray(0)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val KEY_ACTIVE = "selection_active"
        const val KEY_IDS = "selection_ids"
        const val MAX_SAVED = 5000
        const val CHUNK = 500
        const val MIN_CHUNK = 50
        val worker = Executors.newSingleThreadExecutor()
    }
}
