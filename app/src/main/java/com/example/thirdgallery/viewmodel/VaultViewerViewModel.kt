package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.R
import com.example.thirdgallery.database.VaultItemEntity
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.utils.Event
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

    fun unhide(id: Long) {
        worker.execute {
            val failed = repo.restore(listOf(id))
            media.sync(true) { }
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
