package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.helper.CleaningReportStore
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.repo.SuggestionsRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

/** One burst card: its shots (newest first), the shot that is selected, and whether its best shot was already kept. */
data class BurstCardUi(
    val key: Long,
    val uids: List<Long>,
    val bestUid: Long,
    val selectedUid: Long,
    val dateMs: Long,
    val done: Boolean,
    /** The selected shot is a favorite (the heart is red). */
    val favorite: Boolean = false,
)

/**
 * Best from bursts (design): every burst found in the gallery with the recommended shot selected. The user may pick
 * another shot; "Keep selected" moves the other shots of the burst to the Bin, the heart favorites the selected shot
 * and the album button adds it to an album. A card that was dealt with stays as "Best shot kept" until the screen closes.
 */
class BurstsViewModel(application: Application) : AndroidViewModel(application) {

    private enum class Action { KEEP, FAVORITE }

    private class Pending(val action: Action, val key: Long, val uids: List<Long>, val bytes: Long, val value: Boolean = true)

    private val repository = MediaRepository(application)
    private val suggestions = SuggestionsRepository(application)

    @Volatile
    private var current: List<BurstCardUi> = emptyList()

    @Volatile
    private var pending: Pending? = null

    /** Every shot of every burst that is a favorite now; follows the index, so a change made anywhere updates the hearts. */
    @Volatile
    private var favorites: Set<Long> = emptySet()

    private val favoriteWatcher = Observer<Int> { worker.execute { refreshFavorites() } }

    private val _cards = MutableLiveData<List<BurstCardUi>>()
    val cards: LiveData<List<BurstCardUi>> = _cards

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    private val _events = MutableLiveData<Event<PickEvent>>()
    val events: LiveData<Event<PickEvent>> = _events

    init {
        worker.execute {
            val bursts = try {
                suggestions.loadBursts()
            } catch (e: Exception) {
                emptyList()
            }
            current = bursts.map { BurstCardUi(it.uids.first(), it.uids, it.bestUid, it.bestUid, it.dateMs, false) }
            refreshFavorites()
            _loaded.postValue(true)
        }
        repository.favoriteCount().observeForever(favoriteWatcher)
    }

    override fun onCleared() {
        repository.favoriteCount().removeObserver(favoriteWatcher)
        super.onCleared()
    }

    /** Reads which shots are favorites and redraws the hearts of the cards. */
    private fun refreshFavorites() {
        val uids = current.flatMap { it.uids }
        val found = try {
            HashSet<Long>().also { set -> uids.chunked(500).forEach { chunk -> repository.byUids(chunk).forEach { if (it.isFavorite) set.add(it.uid) } } }
        } catch (e: Exception) {
            return
        }
        synchronized(this) {
            favorites = found
            current = current.map { it.copy(favorite = it.selectedUid in found) }
            _cards.postValue(current)
        }
    }

    /** The user picked another shot of the burst [key]. */
    fun select(key: Long, uid: Long) {
        update(key) { if (it.done || uid !in it.uids) it else it.copy(selectedUid = uid) }
    }

    fun selectedOf(key: Long): Long? = current.firstOrNull { it.key == key }?.selectedUid

    /** Keeps the selected shot: the other shots of the burst go to the Bin after the system's confirmation (Android 11+). */
    fun keep(key: Long) {
        val card = current.firstOrNull { it.key == key } ?: return
        if (card.done) return
        val others = card.uids.filter { it != card.selectedUid }
        if (others.isEmpty()) {
            update(key) { it.copy(done = true) }
            return
        }
        request(Action.KEEP, key, others)
    }

    fun favorite(key: Long) {
        val card = current.firstOrNull { it.key == key } ?: return
        if (card.done) return
        // The heart toggles: a favorite shot is removed from Favorites, any other is added.
        request(Action.FAVORITE, key, listOf(card.selectedUid), !card.favorite)
    }

    private fun request(action: Action, key: Long, uids: List<Long>, value: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _events.value = Event(PickEvent.Message(R.string.viewer_unsupported))
            return
        }
        worker.execute {
            val resolver = getApplication<Application>().contentResolver
            val uris = uids.map { MediaUris.ofUid(it) }
            val intent: PendingIntent? = try {
                if (action == Action.KEEP) MediaStore.createTrashRequest(resolver, uris, true) else MediaStore.createFavoriteRequest(resolver, uris, value)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (intent == null) {
                _events.postValue(Event(PickEvent.Message(R.string.bin_failed)))
                return@execute
            }
            // The size of what is about to leave, for the Cleaning report.
            val bytes = if (action == Action.KEEP) CleaningReportStore.sizeOf(resolver, uris) else 0L
            pending = Pending(action, key, uids, bytes, value)
            _events.postValue(Event(PickEvent.Launch(intent)))
        }
    }

    /** The system confirmation was answered; on "yes" the index changes at once so every grid and count follows. */
    fun onRequestResult(accepted: Boolean) {
        val done = pending
        pending = null
        if (!accepted) return
        if (done == null) {
            // The screen was recreated while the system dialog was open: re-read the media index.
            repository.sync(true) { }
            return
        }
        worker.execute {
            if (done.action == Action.KEEP) {
                repository.removeLocalAll(done.uids)
                CleaningReportStore.record(getApplication(), done.bytes, done.uids.size)
                update(done.key) { it.copy(done = true) }
                _events.postValue(Event(PickEvent.Message(R.string.burst_kept_toast)))
            } else {
                repository.setFavoritesLocal(done.uids, done.value)
                synchronized(this) { favorites = if (done.value) favorites + done.uids else favorites - done.uids.toSet() }
                update(done.key) { it }
                val message = if (done.value) R.string.selection_added_favorites else R.string.selection_removed_favorites
                _events.postValue(Event(PickEvent.Message(message, done.uids.size)))
            }
        }
    }

    private fun update(key: Long, change: (BurstCardUi) -> BurstCardUi) {
        synchronized(this) {
            current = current.map { if (it.key == key) change(it).let { c -> c.copy(favorite = c.selectedUid in favorites) } else it }
            _cards.postValue(current)
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
