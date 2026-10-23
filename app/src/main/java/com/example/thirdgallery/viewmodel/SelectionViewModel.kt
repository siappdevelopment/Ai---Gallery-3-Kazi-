package com.example.thirdgallery.viewmodel

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
import com.example.thirdgallery.R
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.utils.Event
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

    private enum class Pending { FAVORITE, TRASH }

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

    val isActive: Boolean get() = _active.value == true

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
        publish(if (uid in current) current - uid else current + uid)
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
        request(Pending.FAVORITE)
    }

    fun moveToBin() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _events.value = Event(SelectionEvent.Message(R.string.viewer_unsupported))
            return
        }
        request(Pending.TRASH)
    }

    private fun request(action: Pending) {
        val uids = snapshot()
        if (uids.isEmpty()) return
        worker.execute {
            val resolver = getApplication<Application>().contentResolver
            val uris = uids.map { MediaUris.ofUid(it) }
            val intent = try {
                if (action == Pending.FAVORITE) {
                    MediaStore.createFavoriteRequest(resolver, uris, true)
                } else {
                    MediaStore.createTrashRequest(resolver, uris, true)
                }
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (intent == null) {
                _events.postValue(Event(SelectionEvent.Message(R.string.bin_failed)))
                return@execute
            }
            pending = action
            pendingUids = uids
            _events.postValue(Event(SelectionEvent.Launch(intent)))
        }
    }

    /** The system confirmation was answered; on "yes" the index is updated at once (grids and counts follow). */
    fun onRequestResult(accepted: Boolean) {
        val action = pending
        val uids = pendingUids
        pending = null
        pendingUids = emptyList()
        if (!accepted) return
        if (action == null) {
            // The screen was recreated while the system dialog was open: re-read the media index.
            repository.sync(true) { }
            return
        }
        worker.execute {
            if (action == Pending.FAVORITE) {
                repository.setFavoritesLocal(uids, true)
            } else {
                repository.removeLocalAll(uids)
            }
            val message = if (action == Pending.FAVORITE) R.string.selection_added_favorites else R.string.selection_moved_bin
            _events.postValue(Event(SelectionEvent.Message(message, uids.size)))
            mainHandler.post { clear() }
        }
    }

    private fun setActive(on: Boolean) {
        _active.value = on
        state[KEY_ACTIVE] = on
    }

    private fun publish(set: Set<Long>) {
        _selected.value = set
        persist(set)
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
        val worker = Executors.newSingleThreadExecutor()
    }
}
