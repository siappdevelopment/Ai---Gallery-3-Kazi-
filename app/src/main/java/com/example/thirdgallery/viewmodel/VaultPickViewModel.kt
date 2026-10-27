package com.example.thirdgallery.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

/**
 * "Add to vault": the gallery to choose from, the selection, and the move itself. Every chosen file is first
 * copied into the vault; then one system request deletes all the originals, and only what the system really
 * deleted stays in the vault.
 */
class VaultPickViewModel(application: Application) : AndroidViewModel(application) {

    /** Copy progress shown on the "Moving to vault" card. */
    class Progress(val done: Int, val total: Int)

    /** How the move ended: [moved] items are in the vault, [failed] could not be moved. */
    class Result(val moved: Int, val failed: Int)

    private val media = MediaRepository(application)
    private val vault = VaultRepository(application)

    val items: LiveData<PagingData<MediaEntity>> =
        media.pagedCollection(AlbumKind.DEVICE, 0L, 0L).cachedIn(viewModelScope)

    /** Photos and videos still in the gallery (what "Select all" selects). */
    val total: LiveData<Int> = media.collectionCount(AlbumKind.DEVICE, 0L, 0L)

    private val selection = LinkedHashSet<Long>()

    private val _count = MutableLiveData(0)
    val count: LiveData<Int> = _count

    private val _progress = MutableLiveData<Progress?>(null)
    val progress: LiveData<Progress?> = _progress

    private val _request = MutableLiveData<Event<PendingIntent>>()
    val request: LiveData<Event<PendingIntent>> = _request

    private val _result = MutableLiveData<Event<Result>>()
    val result: LiveData<Event<Result>> = _result

    @Volatile
    private var staged: List<VaultRepository.Staged> = emptyList()

    @Volatile
    private var requested = 0

    val isMoving: Boolean get() = _progress.value != null

    fun isSelected(uid: Long) = selection.contains(uid)

    fun toggle(uid: Long) {
        if (isMoving) return
        if (!selection.remove(uid)) selection.add(uid)
        _count.value = selection.size
    }

    /** Select all, or clear when everything is already chosen. */
    fun toggleAll() {
        if (isMoving) return
        if (selection.size >= (total.value ?: 0) && selection.isNotEmpty()) {
            selection.clear()
            _count.value = 0
            return
        }
        worker.execute {
            val all = media.viewerUids(AlbumKind.DEVICE, 0L, 0L, MediaFilter.ALL)
            synchronized(selection) { selection.addAll(all) }
            _count.postValue(selection.size)
        }
    }

    fun start() {
        if (isMoving || selection.isEmpty()) return
        val uids = selection.toList()
        requested = uids.size
        _progress.value = Progress(0, uids.size)
        worker.execute {
            val copied = vault.stageAll(uids) { done -> _progress.postValue(Progress(done, uids.size)) }
            if (copied.isEmpty()) {
                finish(Result(0, uids.size))
                return@execute
            }
            val request = try {
                MediaStore.createDeleteRequest(
                    getApplication<Application>().contentResolver,
                    copied.map { MediaUris.ofUid(it.sourceUid) },
                )
            } catch (e: RuntimeException) {
                vault.rollbackAll(copied)
                finish(Result(0, uids.size))
                return@execute
            }
            staged = copied
            _request.postValue(Event(request))
        }
    }

    /** The system request was answered: keep what it deleted, undo the rest. */
    fun onRequestResult(accepted: Boolean) {
        val copied = staged
        staged = emptyList()
        if (copied.isEmpty()) return
        worker.execute {
            if (!accepted) {
                vault.rollbackAll(copied)
                _progress.postValue(null)
                return@execute
            }
            val moved = vault.commitAll(copied)
            finish(Result(moved, requested - moved))
        }
    }

    private fun finish(result: Result) {
        synchronized(selection) { selection.clear() }
        _count.postValue(0)
        _progress.postValue(null)
        _result.postValue(Event(result))
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
