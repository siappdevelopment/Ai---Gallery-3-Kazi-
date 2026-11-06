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
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.repo.VaultRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

/** Something the screen has to show or launch for the selection (system confirmations, toasts). */
sealed class SelectionEvent {
    class Launch(val intent: PendingIntent) : SelectionEvent()
    class Message(@StringRes val res: Int, val count: Int = 0) : SelectionEvent()

    /** Hide finished: [hidden] items are in the vault, [failed] could not be moved. */
    class HideResult(val hidden: Int, val failed: Int) : SelectionEvent()

    /** The vault was just set up for Hide: ask the user to confirm now. */
    object ConfirmHide : SelectionEvent()
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
        selectableTotal = 0
        publish(emptySet())
    }

    fun selectAll() {
        worker.execute {
            val all = try {
                repository.viewerUids(sourceKind, sourceId, AlbumsViewModel.recentSince(), sourceFilter).toHashSet()
            } catch (e: SecurityException) {
                emptySet()
            }
            mainHandler.post {
                selectableTotal = all.size
                publish(all)
            }
        }
    }

    /** "Deselect all": unselects everything at once and stays in selection mode, so another selection can be started. */
    fun deselectAll() {
        publish(emptySet())
    }

    /** How many items "Select all" selected; 0 until it was used. The button turns into "Deselect all" once all are selected. */
    @Volatile
    private var selectableTotal = 0

    private val _allSelected = MutableLiveData(false)

    /** True while every item of the grid is selected. */
    val allSelected: LiveData<Boolean> = _allSelected

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

    /** Delete inside an app album: the selected items leave that album only; the files and every other album keep them. */
    fun removeFromAlbum(albumId: Long) {
        val uids = snapshot()
        if (uids.isEmpty() || albumId <= 0L) return
        worker.execute {
            val message = try {
                com.photovideo.gallerykz.memories.repo.AlbumRepository(getApplication()).removeItems(albumId, uids)
                SelectionEvent.Message(R.string.selection_removed_album, uids.size)
            } catch (e: Exception) {
                SelectionEvent.Message(R.string.bin_failed)
            }
            _events.postValue(Event(message))
            mainHandler.post { clear() }
        }
    }

    private fun request(action: Pending) {
        val selected = snapshot()
        if (selected.isEmpty()) return
        if (action == Pending.TRASH) {
            // One delete at a time; the progress dialog blocks the screen until it is over.
            if (trashRunning) return
            trashRunning = true
            trashTotal = selected.size
            _deleteProgress.value = HideProgress(0, selected.size)
        }
        worker.execute {
            // Favorite / Unfavorite: only the items that really change go to the system (fewer items = a faster request), and
            // the index is updated at once, so the hearts follow the tap; a refused request puts them back.
            val uids = prepareFavorites(action, selected)
            // One system request cannot carry thousands of items (it failed with 2,000+ photos):
            // the selection is sent in chunks, one system confirmation per chunk.
            queue.clear()
            // Delete goes in chunks of 200 like Hide (one system confirmation each); Favorite keeps its larger chunks.
            queue.addAll(uids.chunked(if (action == Pending.TRASH) HIDE_CHUNK else CHUNK))
            processed = 0
            pending = action
            launchNext(action)
        }
    }

    @Volatile
    private var trashRunning = false
    private var trashTotal = 0

    private val _deleteProgress = MutableLiveData<HideProgress?>(null)

    /** Not null while Delete (move to Bin) runs in chunks; the screen shows a progress dialog for it. */
    val deleteProgress: LiveData<HideProgress?> = _deleteProgress

    private fun endTrashProgress() {
        if (!trashRunning) return
        trashRunning = false
        _deleteProgress.postValue(null)
    }

    /** True while the index already shows the result of a Favorite / Unfavorite that the system has not confirmed yet. */
    @Volatile
    private var optimistic = false

    private fun prepareFavorites(action: Pending, selected: List<Long>): List<Long> {
        optimistic = false
        if (action == Pending.TRASH) return selected
        val value = action == Pending.FAVORITE
        val targets = try {
            val favorites = repository.favoritesAmong(selected)
            selected.filter { (it in favorites) != value }
        } catch (e: Exception) {
            return selected
        }
        if (targets.isEmpty()) return selected
        return try {
            repository.setFavoritesLocal(targets, value)
            optimistic = true
            targets
        } catch (e: Exception) {
            selected
        }
    }

    /** The system refused (or the request failed): the items that were shown as changed go back to what they were. */
    private fun revertOptimistic(action: Pending) {
        if (!optimistic) return
        optimistic = false
        val left = queue.flatten()
        if (left.isEmpty()) return
        try {
            repository.setFavoritesLocal(left, action != Pending.FAVORITE)
        } catch (e: Exception) {
            // The next sync reads the real state.
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
            revertOptimistic(action)
            queue.clear()
            pending = null
            pendingUids = emptyList()
            if (processed > 0) finishRequests(action) else {
                endTrashProgress()
                _events.postValue(Event(SelectionEvent.Message(R.string.bin_failed)))
            }
            return
        }
    }

    private fun finishRequests(action: Pending) {
        val done = processed
        optimistic = false
        endTrashProgress()
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
        if (hidePending) {
            onHideResult(accepted)
            return
        }
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
                revertOptimistic(action)
                finishRequests(action)
                return@execute
            }
            when (action) {
                Pending.FAVORITE -> if (!optimistic) repository.setFavoritesLocal(uids, true)
                Pending.UNFAVORITE -> if (!optimistic) repository.setFavoritesLocal(uids, false)
                Pending.TRASH -> repository.removeLocalAll(uids)
            }
            processed += uids.size
            if (action == Pending.TRASH) _deleteProgress.postValue(HideProgress(processed, trashTotal))
            queue.removeFirstOrNull()
            pendingUids = emptyList()
            launchNext(action)
        }
    }

    // ---- Hide: move the selection into the Photo vault

    /** How many items Hide has handled so far (copied, or given up) out of all of them. */
    class HideProgress(val done: Int, val total: Int)

    private val vault by lazy { VaultRepository(getApplication()) }

    private val _hideProgress = MutableLiveData<HideProgress?>(null)

    /** Not null while Hide is running; the screen shows a progress dialog for it. */
    val hideProgress: LiveData<HideProgress?> = _hideProgress

    /** The vault had no PIN yet: its setup was opened, and a successful setup continues with Hide. */
    var awaitingVaultSetup = false

    @Volatile
    private var hidePending = false

    /** Chunks still to do (worker thread only), the copies waiting for the system's answer, and the running counts. */
    private val hideQueue = ArrayDeque<List<Long>>()

    @Volatile
    private var hideStaged: List<VaultRepository.Staged> = emptyList()

    private var hideTotal = 0
    private var hideHandled = 0
    private var hideMoved = 0
    private var hideFailed = 0

    fun onVaultSetupResult(ok: Boolean) {
        val waiting = awaitingVaultSetup
        awaitingVaultSetup = false
        if (waiting && ok) _events.value = Event(SelectionEvent.ConfirmHide)
    }

    /**
     * Hide: every selected item is copied into the vault and its original is then deleted by the system. That is done in chunks of
     * [HIDE_CHUNK], one after the other: copy a chunk, ask the system to delete its originals (one confirmation), keep what it
     * deleted, then the next chunk, until every item has been handled. Each item is in exactly one chunk, a copy whose original
     * is still there is undone, and an item that could not be copied is counted as failed, so nothing is skipped or doubled.
     */
    fun hide() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _events.value = Event(SelectionEvent.Message(R.string.viewer_unsupported))
            return
        }
        if (hidePending) return
        val uids = snapshot()
        if (uids.isEmpty()) return
        hidePending = true
        hideTotal = uids.size
        hideHandled = 0
        hideMoved = 0
        hideFailed = 0
        hideQueue.clear()
        hideQueue.addAll(uids.chunked(HIDE_CHUNK))
        _hideProgress.value = HideProgress(0, uids.size)
        worker.execute { hideNext() }
    }

    /** Copies the next chunk and asks the system to delete its originals (worker thread); a chunk that cannot go on is counted as failed. */
    private fun hideNext() {
        val resolver = getApplication<Application>().contentResolver
        while (true) {
            val chunk = hideQueue.firstOrNull() ?: return finishHide()
            val base = hideHandled
            val copied = vault.stageAll(chunk) { n -> _hideProgress.postValue(HideProgress(base + n, hideTotal)) }
            if (copied.isNotEmpty()) {
                val request = try {
                    MediaStore.createDeleteRequest(resolver, copied.map { MediaUris.ofUid(it.sourceUid) })
                } catch (e: RuntimeException) {
                    vault.rollbackAll(copied)
                    null
                }
                if (request != null) {
                    hideStaged = copied
                    _events.postValue(Event(SelectionEvent.Launch(request)))
                    return
                }
            }
            // Nothing of this chunk could be copied or asked for: all of it is failed, and the next chunk goes on.
            hideFailed += chunk.size
            hideHandled += chunk.size
            hideQueue.removeFirst()
            _hideProgress.postValue(HideProgress(hideHandled, hideTotal))
        }
    }

    /** The system answered for the chunk that was copied: keep what it deleted, undo the rest, and go on with the next chunk. */
    private fun onHideResult(accepted: Boolean) {
        val copied = hideStaged
        hideStaged = emptyList()
        worker.execute {
            if (!accepted) {
                // The user refused: the copies go away, the originals are untouched, and no more questions are asked.
                // What earlier chunks already moved stays in the vault.
                vault.rollbackAll(copied)
                finishHide()
                return@execute
            }
            val chunk = hideQueue.firstOrNull() ?: return@execute finishHide()
            val moved = vault.commitAll(copied)
            hideMoved += moved
            hideFailed += chunk.size - moved
            hideHandled += chunk.size
            hideQueue.removeFirst()
            _hideProgress.postValue(HideProgress(hideHandled, hideTotal))
            hideNext()
        }
    }

    private fun finishHide() {
        val moved = hideMoved
        val failed = hideFailed
        hideQueue.clear()
        hideStaged = emptyList()
        hidePending = false
        _hideProgress.postValue(null)
        if (moved > 0 || failed > 0) _events.postValue(Event(SelectionEvent.HideResult(moved, failed)))
        mainHandler.post { clear() }
    }

    private fun setActive(on: Boolean) {
        _active.value = on
        state[KEY_ACTIVE] = on
    }

    private fun publish(set: Set<Long>) {
        // Set first: the selection observers read it as soon as the selection changes.
        _allSelected.value = selectableTotal > 0 && set.size >= selectableTotal
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
        /** Favorite / Unfavorite items per system request (one confirmation each): bigger = fewer repeated dialogs; a refused chunk is split in two. */
        const val CHUNK = 1000
        const val MIN_CHUNK = 50

        /** Hide handles this many items at a time (the system's limit for one confirmation). */
        const val HIDE_CHUNK = 200
        val worker = Executors.newSingleThreadExecutor()
    }
}
