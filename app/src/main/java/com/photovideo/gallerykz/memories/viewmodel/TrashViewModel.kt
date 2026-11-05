package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.photovideo.gallerykz.memories.repo.BinItem
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.repo.TrashRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

/** The Bin screen: the trashed items and the "Restore all" / "Empty Bin" / "Delete" requests. */
class TrashViewModel(application: Application) : AndroidViewModel(application) {

    enum class Action { RESTORE, DELETE }

    /** A system confirmation to show; [count] items are affected once the user accepts. */
    class Request(val action: Action, val intent: PendingIntent, val count: Int)

    /** How the whole job ended: [count] items were restored or deleted. */
    class Done(val action: Action, val count: Int)

    private val trash = TrashRepository(application)
    private val media = MediaRepository(application)

    @Volatile
    private var current: List<BinItem> = emptyList()

    // One system request cannot carry thousands of items, so a big job is sent in chunks, one confirmation per chunk.
    private val queue = ArrayDeque<List<Long>>()

    @Volatile
    private var pendingAction: Action? = null

    @Volatile
    private var pendingChunk: List<Long> = emptyList()

    @Volatile
    private var processed = 0

    /** How far a Restore / Delete job is: [done] of [total] items handled. */
    class Progress(val action: Action, val done: Int, val total: Int)

    @Volatile
    private var total = 0

    private val _progress = MutableLiveData<Progress?>(null)

    /** Not null while a job runs in chunks; the screen shows a blocking progress dialog for it. */
    val progress: LiveData<Progress?> = _progress

    private val _items = MutableLiveData<List<BinItem>>(emptyList())
    val items: LiveData<List<BinItem>> = _items

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    private val _request = MutableLiveData<Event<Request>>()
    val request: LiveData<Event<Request>> = _request

    private val _done = MutableLiveData<Event<Done>>()
    val done: LiveData<Event<Done>> = _done

    private val _failed = MutableLiveData<Event<Unit>>()
    val failed: LiveData<Event<Unit>> = _failed

    val isSupported: Boolean get() = trash.isSupported

    fun refresh() {
        worker.execute {
            current = try {
                trash.load()
            } catch (e: SecurityException) {
                emptyList()
            }
            _items.postValue(current)
            _loaded.postValue(true)
        }
    }

    /** Restores [selected] items, or every item when null. */
    fun restoreAll(selected: Set<Long>? = null) = ask(Action.RESTORE, selected)

    /** Deletes [selected] items for good, or every item when null. */
    fun emptyBin(selected: Set<Long>? = null) = ask(Action.DELETE, selected)

    private fun ask(action: Action, selected: Set<Long>?) {
        worker.execute {
            val uids = current.map { it.uid }.filter { selected == null || it in selected }
            if (uids.isEmpty() || pendingAction != null) return@execute
            queue.clear()
            queue.addAll(uids.chunked(CHUNK))
            processed = 0
            total = uids.size
            pendingAction = action
            _progress.postValue(Progress(action, 0, total))
            launchNext(action)
        }
    }

    /** Builds the system request for the first chunk of the queue (worker thread); a chunk that is refused is split in two. */
    private fun launchNext(action: Action) {
        while (true) {
            val chunk = queue.firstOrNull() ?: return finish(action)
            val intent = try {
                if (action == Action.RESTORE) trash.trashRequest(chunk, false) else trash.deleteRequest(chunk)
            } catch (e: Exception) {
                null
            }
            if (intent != null) {
                pendingChunk = chunk
                _request.postValue(Event(Request(action, intent, chunk.size)))
                return
            }
            if (chunk.size > MIN_CHUNK) {
                val half = chunk.size / 2
                queue.removeFirst()
                queue.addFirst(chunk.subList(half, chunk.size))
                queue.addFirst(chunk.subList(0, half))
                continue
            }
            // Even a small chunk is refused: stop here, report what was done so far (or the failure when nothing was).
            val done = processed
            queue.clear()
            pendingAction = null
            pendingChunk = emptyList()
            if (done > 0) finish(action) else {
                _progress.postValue(null)
                _failed.postValue(Event(Unit))
            }
            return
        }
    }

    /** The whole job is over: what the system accepted is done; the list is read again and a restored item returns to the gallery. */
    private fun finish(action: Action) {
        val done = processed
        queue.clear()
        pendingAction = null
        pendingChunk = emptyList()
        processed = 0
        _progress.postValue(null)
        if (done <= 0) return
        if (action == Action.RESTORE) media.sync(true) { }
        refresh()
        _done.postValue(Event(Done(action, done)))
    }

    /** The system confirmation was answered; on "yes" the next chunk is asked for, on "no" the job stops. */
    fun onResult(accepted: Boolean) {
        worker.execute {
            val action = pendingAction
            if (action == null) {
                // The screen was recreated while the system dialog was open: just read everything again.
                if (accepted) {
                    media.sync(true) { }
                    refresh()
                }
                return@execute
            }
            if (!accepted) {
                // Cancelled: what the earlier chunks did stays done; the rest is dropped.
                finish(action)
                return@execute
            }
            processed += pendingChunk.size
            _progress.postValue(Progress(action, processed, total))
            queue.removeFirstOrNull()
            pendingChunk = emptyList()
            launchNext(action)
        }
    }

    private companion object {
        /** The same chunk as Hide: 200 items per system confirmation. */
        const val CHUNK = 200
        const val MIN_CHUNK = 50
        val worker = Executors.newSingleThreadExecutor()
    }
}
