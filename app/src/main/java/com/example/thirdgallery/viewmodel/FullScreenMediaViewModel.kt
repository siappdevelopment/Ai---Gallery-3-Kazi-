package com.example.thirdgallery.viewmodel

import android.app.Application
import android.app.RecoverableSecurityException
import android.app.PendingIntent
import android.content.ContentValues
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.helper.DetailRow
import com.example.thirdgallery.helper.MediaDetailsReader
import com.example.thirdgallery.utils.Event
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.repo.VaultRepository
import java.util.concurrent.Executors

/** Outcome of a rename: done, needs the user's consent (Android 10), or failed with a message. */
sealed class RenameEvent {
    object Done : RenameEvent()
    class Consent(val sender: IntentSender) : RenameEvent()
    class Failed(@StringRes val message: Int) : RenameEvent()
}

/**
 * The photo viewer: the ordered ids of the album / gallery the item was opened from (the same
 * order as the grid it was tapped in), and the details of the item that is on screen.
 */
class FullScreenMediaViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val vault = VaultRepository(application)

    private val kind = AlbumKind.fromKey(state.get<String>(ARG_KIND))
    private val albumId = state.get<Long>(ARG_ID) ?: 0L
    private val query = state.get<String>(ARG_QUERY).orEmpty()
    private val filter = MediaFilter.entries.getOrNull(state.get<Int>(ARG_FILTER) ?: 0) ?: MediaFilter.ALL

    @Volatile
    private var list: List<Long> = emptyList()

    private val _uids = MutableLiveData<List<Long>>()
    val uids: LiveData<List<Long>> = _uids

    private val _current = MutableLiveData<MediaEntity?>()
    val current: LiveData<MediaEntity?> = _current

    /** App album the viewer was opened from (0 when it is not one): "Move to album" takes the item out of it. */
    val sourceAlbumId: Long = if (kind == AlbumKind.USER) albumId else 0L

    /** Device folder the viewer was opened from (0 = whole gallery or not a folder). */
    val sourceFolderId: Long = if (kind == AlbumKind.DEVICE) albumId else 0L

    private val _renameName = MutableLiveData<Event<String>>()
    val renameName: LiveData<Event<String>> = _renameName

    private val _rename = MutableLiveData<Event<RenameEvent>>()
    val rename: LiveData<Event<RenameEvent>> = _rename

    @Volatile
    private var pendingRename: Pair<Long, String>? = null

    private val _details = MutableLiveData<Event<List<DetailRow>>>()
    val details: LiveData<Event<List<DetailRow>>> = _details

    /** True while the vault setup opened from "Move to vault" is running; the move continues when it succeeds. */
    var awaitingVaultSetup = false

    private var stagedMove: VaultRepository.Staged? = null

    private val _vaultRequest = MutableLiveData<Event<PendingIntent>>()
    val vaultRequest: LiveData<Event<PendingIntent>> = _vaultRequest

    /** True when the item is now in the vault, false when the move failed (a cancelled move says nothing). */
    private val _vaultResult = MutableLiveData<Event<Boolean>>()
    val vaultResult: LiveData<Event<Boolean>> = _vaultResult

    /** The item that is (or should be) on screen; survives rotation and process death. */
    val currentUid: Long get() = state.get<Long>(ARG_UID) ?: 0L

    init {
        // A move interrupted earlier (app killed during the system dialog) is settled in the background.
        worker.execute {
            try {
                vault.settleInterrupted()
            } catch (e: Exception) {
                // Retried the next time a viewer or the vault opens.
            }
        }
        worker.execute {
            val fixed = state.get<LongArray>(ARG_UIDS)
            list = if (fixed != null) fixed.toList() else try {
                repository.viewerUids(kind, albumId, AlbumsViewModel.recentSince(), filter, query)
            } catch (e: Exception) {
                emptyList()
            }
            _uids.postValue(list)
        }
    }

    fun select(uid: Long) {
        state[ARG_UID] = uid
        worker.execute { _current.postValue(repository.find(uid)) }
    }

    fun loadDetails(media: MediaEntity) {
        worker.execute {
            _details.postValue(Event(MediaDetailsReader.read(getApplication(), media)))
        }
    }

    /** Reads the current file name, then the screen opens the Rename sheet with it. */
    fun requestRename(media: MediaEntity) {
        worker.execute {
            val name = MediaDetailsReader.fileName(getApplication(), media)
            if (name == null) {
                _rename.postValue(Event(RenameEvent.Failed(R.string.viewer_rename_failed)))
            } else {
                _renameName.postValue(Event(name))
            }
        }
    }

    fun rename(uid: Long, newName: String) {
        pendingRename = uid to newName
        worker.execute { performRename(uid, newName) }
    }

    /** The user agreed in the system dialog (Android 10): run the same rename again. */
    fun retryRename() {
        val pending = pendingRename ?: return
        worker.execute { performRename(pending.first, pending.second) }
    }

    private fun performRename(uid: Long, newName: String) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        val event = try {
            val rows = getApplication<Application>().contentResolver.update(MediaUris.ofUid(uid), values, null, null)
            if (rows > 0) RenameEvent.Done else RenameEvent.Failed(R.string.viewer_rename_failed)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                RenameEvent.Consent(e.userAction.actionIntent.intentSender)
            } else {
                RenameEvent.Failed(R.string.viewer_rename_failed)
            }
        } catch (e: IllegalStateException) {
            RenameEvent.Failed(R.string.viewer_rename_exists)
        } catch (e: IllegalArgumentException) {
            RenameEvent.Failed(R.string.viewer_rename_invalid)
        }
        if (event is RenameEvent.Done) pendingRename = null
        _rename.postValue(Event(event))
    }

    /** The item left the album this viewer shows (Move): drop it from the swipe list only. */
    fun forget(uid: Long) {
        list = list.filter { it != uid }
        _uids.postValue(list)
    }

    fun setFavorite(uid: Long, favorite: Boolean) {
        worker.execute {
            repository.setFavoriteLocal(uid, favorite)
            _current.postValue(repository.find(uid))
        }
    }

    /** The item was deleted from the device: drop it from the viewer and from the index. */
    fun remove(uid: Long) {
        worker.execute {
            repository.removeLocal(uid)
            list = list.filter { it != uid }
            _uids.postValue(list)
        }
    }

    /**
     * "Hide" was confirmed: copies [uid] into the private vault folder, then hands the screen the system
     * request that deletes the original. Nothing is lost if that request is refused: the copy is dropped again.
     */
    fun moveToVault(uid: Long) {
        worker.execute {
            val staged = vault.stage(uid)
            if (staged == null) {
                _vaultResult.postValue(Event(false))
                return@execute
            }
            val request = try {
                MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, listOf(MediaUris.ofUid(uid)))
            } catch (e: RuntimeException) {
                vault.rollback(staged)
                _vaultResult.postValue(Event(false))
                return@execute
            }
            stagedMove = staged
            _vaultRequest.postValue(Event(request))
        }
    }

    /** The system request was answered: finish the move, or undo it when it was refused. */
    fun onVaultRequestResult(accepted: Boolean) {
        val staged = stagedMove ?: return
        stagedMove = null
        worker.execute {
            if (accepted && vault.commit(staged)) {
                list = list.filter { it != staged.sourceUid }
                _uids.postValue(list)
                _vaultResult.postValue(Event(true))
            } else {
                vault.rollback(staged)
                if (accepted) _vaultResult.postValue(Event(false))
            }
        }
    }

    companion object {
        const val ARG_UID = "uid"
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
        const val ARG_FILTER = "filter"
        const val ARG_QUERY = "query"

        /** A fixed list to swipe through (a highlight) instead of an album or the gallery. */
        const val ARG_UIDS = "uids"
        private val worker = Executors.newSingleThreadExecutor()
    }
}
