package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.VaultItemEntity
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.repo.VaultRepository
import com.photovideo.gallerykz.memories.utils.Event
import java.util.concurrent.Executors

/**
 * Opening a vault item: the items of the folder it was tapped in (in that order, following the vault live, so an
 * item that is unhidden or deleted simply drops out), and the Unhide / Delete actions for the one on screen.
 */
class VaultViewerViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)
    private val media = MediaRepository(application)
    private val order: List<Long> = state.get<LongArray>(ARG_IDS)?.toList().orEmpty()

    val items: LiveData<List<VaultItemEntity>> = MediatorLiveData<List<VaultItemEntity>>().apply {
        addSource(repo.observeItems()) { all ->
            val byId = all.associateBy { it.id }
            value = order.mapNotNull { byId[it] }
        }
    }

    /** The item that is (or should be) on screen; survives rotation and process death. */
    var currentId: Long
        get() = state.get<Long>(ARG_START) ?: 0L
        set(value) {
            state[ARG_START] = value
        }

    private val _message = MutableLiveData<Event<Int>>()
    val message: LiveData<Event<Int>> = _message

    /** 0 of 1 while the item is being restored, null otherwise; the screen shows a dialog nobody can dismiss for that time. */
    class Progress(val done: Int, val total: Int)

    private val _unhideProgress = MutableLiveData<Progress?>(null)
    val unhideProgress: LiveData<Progress?> = _unhideProgress

    /** Restores the item. The progress is cleared in `finally`, so the dialog goes away whatever happens; a second start is ignored. */
    fun unhide(id: Long) {
        if (_unhideProgress.value != null) return
        _unhideProgress.value = Progress(0, 1)
        worker.execute {
            var failed = 1
            try {
                failed = repo.restore(listOf(id)) { n -> _unhideProgress.postValue(Progress(n, 1)) }
                media.sync(true) { }
            } catch (e: Exception) {
                failed = 1
            } finally {
                _unhideProgress.postValue(null)
            }
            _message.postValue(Event(if (failed > 0) R.string.vault_action_failed else R.string.vault_unhidden))
        }
    }

    fun delete(id: Long) {
        worker.execute {
            repo.deleteForever(listOf(id))
            _message.postValue(Event(R.string.vault_deleted))
        }
    }

    companion object {
        const val ARG_IDS = "vault_viewer_ids"
        const val ARG_START = "vault_viewer_start"
        private val worker = Executors.newSingleThreadExecutor()
    }
}
