package com.example.thirdgallery.viewmodel

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.database.UserAlbumRow
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.AlbumRepository
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.repo.MediaTransferRepository
import com.example.thirdgallery.repo.TransferResult
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

/**
 * Backs the "Add to album" sheet for one Journal day: the items to add are every photo and video
 * of that day under the filter that was active when the sheet opened.
 */
class AddToAlbumViewModel(
    application: Application,
    state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val albumRepository = AlbumRepository(application)
    private val mediaRepository = MediaRepository(application)
    private val files = MediaTransferRepository(application)

    private val dayKey: Long = state.get<Long>(ARG_DAY_KEY) ?: 0L
    private val filter: MediaFilter =
        MediaFilter.entries.getOrNull(state.get<Int>(ARG_FILTER) ?: 0) ?: MediaFilter.ALL

    /** Opened from the Albums tab "New" button: only creates an empty album, no items to add. */
    val isCreateOnly: Boolean = dayKey == CREATE_ONLY

    /** Opened from the multi-selection: the items are the chosen ones. */
    val isSelection: Boolean = dayKey == SELECTION

    /** "Move" instead of "Add": the items also leave the app album they were opened from. */
    val isMove: Boolean = state.get<Boolean>(ARG_MOVE) ?: false
    private val moveFrom: Long = state.get<Long>(ARG_SOURCE) ?: 0L

    /** Move takes the items out of the app album they came from (membership), not out of a folder. */
    val leavesAlbum: Boolean get() = isMove && moveFrom > 0

    /** "Copy to album": an app album gets the items as members; a device folder gets real file copies. */
    val isCopy: Boolean = state.get<Boolean>(ARG_COPY) ?: false

    /** A fixed title for the sheet (0 = the default one for the mode). */
    val titleRes: Int = state.get<Int>(ARG_TITLE) ?: 0

    @Volatile
    private var uids: List<Long> = emptyList()

    @Volatile
    private var busy = false

    /**
     * Every album the items can go to: the user's albums first, then the device folders (Camera,
     * Screenshots, Download, ...) that have no app album of the same name. Picking a folder adds
     * the items to an app album of that name.
     */
    val albums: LiveData<List<PickAlbum>> = MediatorLiveData<List<PickAlbum>>().also { merged ->
        val user = albumRepository.albums
        val device = mediaRepository.deviceAlbums()
        val update = {
            val mine = user.value.orEmpty()
            val taken = mine.map { it.name.trim().lowercase() }.toHashSet()
            val list = ArrayList<PickAlbum>(mine.size + device.value.orEmpty().size)
            mine.forEach { list.add(PickAlbum(it.name, it.total, it.cover, it)) }
            device.value.orEmpty().forEach {
                val name = it.name.trim()
                if (name.isNotEmpty() && it.total > 0 && taken.add(name.lowercase())) {
                    list.add(PickAlbum(name, it.total, it.cover0, null, it.id))
                }
            }
            merged.value = list
        }
        merged.addSource(user) { update() }
        merged.addSource(device) { update() }
    }

    private val _itemCount = MutableLiveData(state.get<Int>(ARG_COUNT) ?: 0)
    val itemCount: LiveData<Int> = _itemCount

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    private class PendingMove(val items: List<Long>, val path: String, val albumName: String)

    @Volatile
    private var pendingMove: PendingMove? = null

    private val _consent = MutableLiveData<Event<PendingIntent>>()
    val consent: LiveData<Event<PendingIntent>> = _consent

    private val _transfer = MutableLiveData<Event<TransferOutcome>>()
    val transfer: LiveData<Event<TransferOutcome>> = _transfer

    private val _result = MutableLiveData<Event<AlbumRepository.Result>>()
    val result: LiveData<Event<AlbumRepository.Result>> = _result

    init {
        if (isCreateOnly) {
            _itemCount.value = 0
            _loaded.value = true
        } else if (isSelection) {
            uids = state.get<LongArray>(ARG_UIDS)?.toList().orEmpty()
            _itemCount.value = uids.size
            _loaded.value = true
        } else loader.execute {
            try {
                val list = mediaRepository.uidsOfDay(dayKey, filter)
                uids = list
                _itemCount.postValue(list.size)
            } catch (e: Exception) {
                uids = emptyList()
            }
            _loaded.postValue(true)
        }
    }

    fun addTo(album: PickAlbum) {
        if (!canStart()) return
        val existing = album.user
        val physical = album.bucketId != 0L && (isMove || isCopy) && (if (isMove) files.canMove else files.canCopy)
        when {
            existing != null -> albumRepository.addToAlbum(existing, uids, ::finish, moveFrom)
            physical -> transferToFolder(album)
            else -> albumRepository.createAlbum(album.name, uids, ::finish, moveFrom)
        }
    }

    /** Copy / Move into a real device folder: files are copied (originals stay) or relocated. */
    private fun transferToFolder(album: PickAlbum) {
        val items = uids
        loader.execute {
            val path = files.folderPath(album.bucketId)
            if (path == null) {
                busy = false
                _transfer.postValue(Event(TransferOutcome(album.name, 0, items.size, isMove, false)))
                return@execute
            }
            if (isMove) {
                // The system has to confirm before files can be relocated.
                pendingMove = PendingMove(items, path, album.name)
                _consent.postValue(Event(files.writeRequest(items)))
            } else {
                finishTransfer(album.name, files.copy(items, path), items.size)
            }
        }
    }

    /** The system confirmation for a Move was answered. */
    fun onConsent(accepted: Boolean) {
        val pending = pendingMove
        pendingMove = null
        if (!accepted || pending == null) {
            busy = false
            return
        }
        loader.execute {
            val result = files.move(pending.items, pending.path)
            // Items that left for the folder are no longer members of the app album they were moved from.
            if (moveFrom > 0 && result.done.isNotEmpty()) albumRepository.removeItems(moveFrom, result.done)
            finishTransfer(pending.albumName, result, pending.items.size)
        }
    }

    private fun finishTransfer(albumName: String, result: TransferResult, total: Int) {
        // The index re-reads MediaStore, so folder counts and the gallery follow the new files.
        if (result.done.isNotEmpty()) mediaRepository.sync(true) { }
        busy = false
        val left = isMove && moveFrom > 0 && result.done.isNotEmpty()
        _transfer.postValue(Event(TransferOutcome(albumName, result.done.size, total - result.done.size, isMove, left)))
    }

    fun createAndAdd(name: String) {
        if (!canStart()) return
        albumRepository.createAlbum(name, uids, ::finish, moveFrom)
    }

    /** Ignores taps while a previous one is still running or the items are not loaded yet. */
    private fun canStart(): Boolean {
        if (busy || _loaded.value != true) return false
        busy = true
        return true
    }

    private fun finish(result: AlbumRepository.Result) {
        busy = false
        _result.postValue(Event(result))
    }

    companion object {
        const val ARG_DAY_KEY = "dayKey"
        const val ARG_FILTER = "filter"
        const val ARG_COUNT = "count"
        const val CREATE_ONLY = -1L
        const val SELECTION = -2L
        const val ARG_UIDS = "uids"
        const val ARG_MOVE = "move"
        const val ARG_SOURCE = "source"
        const val ARG_COPY = "copy"
        const val ARG_TITLE = "title"
        private val loader = Executors.newSingleThreadExecutor()
    }
}

/** One row of the "Add to album" list: an app album ([user] set) or a device folder. */
data class PickAlbum(val name: String, val total: Int, val cover: Long?, val user: UserAlbumRow?, val bucketId: Long = 0L)

/** The result of a Copy / Move into a device folder: [failed] items did not make it. */
class TransferOutcome(
    val albumName: String,
    val done: Int,
    val failed: Int,
    val move: Boolean,
    /** The moved items also left the app album they came from. */
    val leftAlbum: Boolean,
)
