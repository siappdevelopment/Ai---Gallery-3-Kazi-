package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.R
import com.example.thirdgallery.database.VaultFolderEntity
import com.example.thirdgallery.database.VaultItemEntity
import com.example.thirdgallery.model.VaultFolderKey
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

/**
 * One folder of the vault (All items, Photos, Videos or a folder of the user): its items, and the selection
 * with its Unhide / Move / Delete actions. The items follow the vault live, so counts never go stale.
 */
class VaultFolderViewModel(
    application: Application,
    state: SavedStateHandle,
) : AndroidViewModel(application) {

    /** What a finished action tells the screen: a message, and how many items it concerned. */
    class Done(val messageRes: Int, val count: Int, val name: String? = null)

    val key: String = state.get<String>(ARG_KEY) ?: VaultFolderKey.ALL

    private val repo = VaultRepository(application)
    private val media = MediaRepository(application)
    private val allItems = repo.observeItems()
    private val allFolders = repo.observeFolders()

    private val _items = MediatorLiveData<List<VaultItemEntity>>().apply {
        fun update() {
            val items = allItems.value ?: return
            // A folder's screen only needs the user folders list to be loaded to know it still exists.
            value = repo.filter(items, key)
        }
        addSource(allItems) { update() }
    }
    val items: LiveData<List<VaultItemEntity>> = _items

    /** Title of the screen: the built-in view's name or the folder's own. */
    val title: LiveData<String> = MediatorLiveData<String>().apply {
        addSource(allFolders) { folders ->
            val app = getApplication<Application>()
            value = when (key) {
                VaultFolderKey.PHOTOS -> app.getString(R.string.vault_folder_photos)
                VaultFolderKey.VIDEOS -> app.getString(R.string.vault_folder_videos)
                VaultFolderKey.ALL -> app.getString(R.string.vault_folder_all)
                else -> folders.firstOrNull { it.id == VaultFolderKey.userId(key) }?.name ?: app.getString(R.string.vault_folder_all)
            }
        }
    }

    /** The user's folders, for the Move sheet. */
    val folders: LiveData<List<VaultFolderEntity>> = allFolders

    /** Every item with its folder, so the Move sheet can show how many items each folder holds. */
    val everything: LiveData<List<VaultItemEntity>> = allItems

    private val selection = LinkedHashSet<Long>()

    private val _selected = MutableLiveData<Set<Long>>(emptySet())
    val selected: LiveData<Set<Long>> = _selected

    private val _selecting = MutableLiveData(state.get<Boolean>(STATE_SELECTING) == true)
    val selecting: LiveData<Boolean> = _selecting

    private val handle = state

    private val _done = MutableLiveData<Event<Done>>()
    val done: LiveData<Event<Done>> = _done

    val isSelecting: Boolean get() = _selecting.value == true

    fun isSelected(id: Long) = selection.contains(id)

    fun beginSelection() = setSelecting(true)

    /** Leaves selection mode and forgets the chosen items. */
    fun endSelection() {
        selection.clear()
        _selected.value = emptySet()
        setSelecting(false)
    }

    private fun setSelecting(on: Boolean) {
        handle[STATE_SELECTING] = on
        _selecting.value = on
    }

    fun toggle(id: Long) {
        if (!selection.remove(id)) selection.add(id)
        _selected.value = selection.toSet()
    }

    fun selectAll() {
        selection.addAll(_items.value.orEmpty().map { it.id })
        _selected.value = selection.toSet()
    }

    /** Items that no longer exist (moved, deleted) drop out of the selection. */
    fun prune() {
        val present = _items.value.orEmpty().map { it.id }.toSet()
        if (selection.retainAll(present)) _selected.value = selection.toSet()
    }

    fun snapshot(): List<Long> = selection.toList()

    /** "Unhide": the selected items go back to the gallery. */
    fun unhide() {
        val ids = snapshot()
        if (ids.isEmpty()) return
        worker.execute {
            val failed = repo.restore(ids)
            media.sync(true) { }
            postDone(ids.size - failed, failed, R.string.vault_unhidden)
        }
    }

    /** "Delete forever": no Bin, no way back. */
    fun deleteForever() {
        val ids = snapshot()
        if (ids.isEmpty()) return
        worker.execute {
            repo.deleteForever(ids)
            postDone(ids.size, 0, R.string.vault_deleted)
        }
    }

    fun moveTo(folder: VaultFolderEntity) {
        val ids = snapshot()
        if (ids.isEmpty()) return
        worker.execute {
            repo.moveToFolder(ids, folder.id)
            postDone(ids.size, 0, R.string.vault_moved_to_folder, folder.name)
        }
    }

    private fun postDone(count: Int, failed: Int, success: Int, name: String? = null) {
        val message = if (failed > 0) R.string.vault_action_failed else success
        _done.postValue(Event(Done(message, if (failed > 0) failed else count, name)))
    }

    /** The action finished: the selection ends. */
    fun finishAction() = endSelection()

    companion object {
        const val ARG_KEY = "vault_folder_key"
        private const val STATE_SELECTING = "vault_selecting"
        private val worker = Executors.newSingleThreadExecutor()
    }
}
