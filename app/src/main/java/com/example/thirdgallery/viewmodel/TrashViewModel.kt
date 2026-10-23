package com.example.thirdgallery.viewmodel

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.repo.BinItem
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.repo.TrashRepository
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

/** The Bin screen: the trashed items and the "Restore all" / "Empty Bin" requests. */
class TrashViewModel(application: Application) : AndroidViewModel(application) {

    enum class Action { RESTORE, DELETE }

    /** A system confirmation to show; [count] items are affected once the user accepts. */
    class Request(val action: Action, val intent: PendingIntent, val count: Int)

    private val trash = TrashRepository(application)
    private val media = MediaRepository(application)

    @Volatile
    private var current: List<BinItem> = emptyList()

    @Volatile
    private var pending: Request? = null

    private val _items = MutableLiveData<List<BinItem>>(emptyList())
    val items: LiveData<List<BinItem>> = _items

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    private val _request = MutableLiveData<Event<Request>>()
    val request: LiveData<Event<Request>> = _request

    private val _done = MutableLiveData<Event<Request>>()
    val done: LiveData<Event<Request>> = _done

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

    fun restoreAll() = ask(Action.RESTORE)

    fun emptyBin() = ask(Action.DELETE)

    private fun ask(action: Action) {
        worker.execute {
            val uids = current.map { it.uid }
            if (uids.isEmpty()) return@execute
            val intent = try {
                if (action == Action.RESTORE) trash.trashRequest(uids, false) else trash.deleteRequest(uids)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (intent == null) {
                _failed.postValue(Event(Unit))
                return@execute
            }
            val request = Request(action, intent, uids.size)
            pending = request
            _request.postValue(Event(request))
        }
    }

    /** The system confirmation was answered. A restored item is read back into the gallery right away. */
    fun onResult(accepted: Boolean) {
        val request = pending
        pending = null
        if (!accepted) return
        if (request == null || request.action == Action.RESTORE) media.sync(true) { }
        refresh()
        if (request != null) _done.value = Event(request)
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
