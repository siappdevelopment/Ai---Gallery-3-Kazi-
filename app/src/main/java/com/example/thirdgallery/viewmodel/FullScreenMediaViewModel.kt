package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.MediaRepository
import java.util.concurrent.Executors

/**
 * The photo viewer: the ordered ids of the album / gallery the item was opened from (the same
 * order as the grid it was tapped in), and the details of the item that is on screen.
 */
class FullScreenMediaViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val kind = AlbumKind.fromKey(state.get<String>(ARG_KIND))
    private val albumId = state.get<Long>(ARG_ID) ?: 0L
    private val filter = MediaFilter.entries.getOrNull(state.get<Int>(ARG_FILTER) ?: 0) ?: MediaFilter.ALL

    @Volatile
    private var list: List<Long> = emptyList()

    private val _uids = MutableLiveData<List<Long>>()
    val uids: LiveData<List<Long>> = _uids

    private val _current = MutableLiveData<MediaEntity?>()
    val current: LiveData<MediaEntity?> = _current

    /** The item that is (or should be) on screen; survives rotation and process death. */
    val currentUid: Long get() = state.get<Long>(ARG_UID) ?: 0L

    init {
        worker.execute {
            list = try {
                repository.viewerUids(kind, albumId, AlbumsViewModel.recentSince(), filter)
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

    companion object {
        const val ARG_UID = "uid"
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
        const val ARG_FILTER = "filter"
        private val worker = Executors.newSingleThreadExecutor()
    }
}
