package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import android.app.PendingIntent
import com.photovideo.gallerykz.memories.model.MediaFilter
import com.photovideo.gallerykz.memories.repo.TrashRepository
import com.photovideo.gallerykz.memories.utils.Event
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.photovideo.gallerykz.memories.database.AlbumCardRow
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.model.AlbumCard
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.AlbumSort
import com.photovideo.gallerykz.memories.repo.AlbumRepository
import com.photovideo.gallerykz.memories.repo.MediaRepository
import java.util.Locale

/** What the Albums tab shows right now (after sorting, searching and "View all"). */
data class AlbumsUi(
    val cards: List<AlbumCard>,
    /** All albums, ignoring the search, for the "N albums" line. */
    val totalAlbums: Int,
    val searching: Boolean,
    val canToggleAll: Boolean,
)

/**
 * Albums tab: device folders (MediaStore buckets) plus the albums the user created, both read from
 * Room, with sort, search, "View all" and the smart-collection counts. Shared at activity scope with
 * the sort sheet.
 */
class AlbumsViewModel(application: Application) : AndroidViewModel(application) {

    private val mediaRepository = MediaRepository(application)
    private val albumRepository = AlbumRepository(application)

    private val deviceAlbums = mediaRepository.deviceAlbums()
    private val userAlbums = albumRepository.albumCards

    private val _sort = MutableLiveData(AlbumSort.NEWEST)
    val sort: LiveData<AlbumSort> = _sort

    private val _query = MutableLiveData("")
    val query: LiveData<String> = _query

    private val _showAll = MutableLiveData(false)
    val showAll: LiveData<Boolean> = _showAll

    /** Smart collection sizes. "Recently added" = the last 30 days. */
    val favoriteCount: LiveData<Int> = mediaRepository.collectionCount(AlbumKind.FAVORITES, 0, 0)
    val videoCount: LiveData<Int> = mediaRepository.collectionCount(AlbumKind.VIDEOS, 0, 0)
    val screenshotCount: LiveData<Int> = mediaRepository.collectionCount(AlbumKind.SCREENSHOTS, 0, 0)
    val recentCount: LiveData<Int> = mediaRepository.collectionCount(AlbumKind.RECENT, 0, recentSince())

    val ui: LiveData<AlbumsUi> = MediatorLiveData<AlbumsUi>().also { result ->
        val update = {
            result.value = build(
                deviceAlbums.value.orEmpty(),
                userAlbums.value.orEmpty(),
                _sort.value ?: AlbumSort.NEWEST,
                _query.value.orEmpty(),
                _showAll.value == true,
            )
        }
        result.addSource(deviceAlbums) { update() }
        result.addSource(userAlbums) { update() }
        result.addSource(_sort) { update() }
        result.addSource(_query) { update() }
        result.addSource(_showAll) { update() }
    }

    private val _synced = MutableLiveData(false)

    /** True once a MediaStore sync finished, so "no albums" is never shown before the first scan. */
    val synced: LiveData<Boolean> = _synced

    /** Makes sure the media index is up to date (cheap when nothing changed). */
    fun refresh() {
        if (!MediaPermissionHelper.hasAccess(getApplication())) {
            _synced.value = false
            return
        }
        mediaRepository.sync(false) { _synced.postValue(true) }
    }

    /** The album card the user asked to delete; kept here so it survives until the confirmation. */
    @Volatile
    var pendingDelete: AlbumCard? = null

    private val _trashRequest = MutableLiveData<Event<PendingIntent>>()

    /** System confirmation to move a device folder's items to the Bin. */
    val trashRequest: LiveData<Event<PendingIntent>> = _trashRequest

    private val _deleteResult = MutableLiveData<Event<Boolean>>()

    /** True when the album / folder was deleted, false when it could not be. */
    val deleteResult: LiveData<Event<Boolean>> = _deleteResult

    /** The user confirmed: an app album is removed (its items stay), a device folder goes to the Bin. */
    fun confirmDelete() {
        val card = pendingDelete ?: return
        if (card.kind == AlbumKind.USER) {
            pendingDelete = null
            albumRepository.deleteAlbum(card.id) { _deleteResult.postValue(Event(true)) }
            return
        }
        worker.execute {
            val request = try {
                val uids = mediaRepository.viewerUids(AlbumKind.DEVICE, card.id, 0L, MediaFilter.ALL)
                if (uids.isEmpty()) null else TrashRepository(getApplication()).trashRequest(uids, true)
            } catch (e: Exception) {
                null
            }
            if (request == null) {
                pendingDelete = null
                _deleteResult.postValue(Event(false))
            } else {
                _trashRequest.postValue(Event(request))
            }
        }
    }

    /** The system dialog for a folder was answered; the folder's items are gone from the gallery on accept. */
    fun onTrashResult(accepted: Boolean) {
        pendingDelete = null
        if (!accepted) return
        mediaRepository.sync(true) { _deleteResult.postValue(Event(true)) }
    }

    private val _renameResult = MutableLiveData<Event<AlbumRepository.Failure?>>()

    /** null inside the event = renamed; otherwise why it failed. */
    val renameResult: LiveData<Event<AlbumRepository.Failure?>> = _renameResult

    fun renameAlbum(card: AlbumCard, newName: String) {
        albumRepository.renameAlbum(card.id, newName) { _renameResult.postValue(Event(it.failure)) }
    }

    fun setSort(sort: AlbumSort) {
        if (_sort.value != sort) _sort.value = sort
    }

    fun setQuery(text: String) {
        if (_query.value != text) _query.value = text
    }

    fun toggleShowAll() {
        _showAll.value = _showAll.value != true
    }

    private fun build(
        device: List<AlbumCardRow>,
        user: List<AlbumCardRow>,
        sort: AlbumSort,
        query: String,
        showAll: Boolean,
    ): AlbumsUi {
        val all = ArrayList<AlbumCard>(device.size + user.size)
        user.forEach { all.add(it.toCard(AlbumKind.USER)) }
        device.forEach { all.add(it.toCard(AlbumKind.DEVICE)) }

        val sorted = when (sort) {
            AlbumSort.NEWEST -> all.sortedByDescending { it.lastDate }
            AlbumSort.OLDEST -> all.sortedBy { it.lastDate }
            AlbumSort.NAME -> all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        val q = query.trim().lowercase(Locale.getDefault())
        val searching = q.isNotEmpty()
        val filtered = if (searching) sorted.filter { it.name.lowercase(Locale.getDefault()).contains(q) } else sorted
        val shown = if (showAll || searching) filtered else filtered.take(RECENT_COUNT)
        return AlbumsUi(shown, all.size, searching, canToggleAll = !searching && sorted.size > RECENT_COUNT)
    }

    private fun AlbumCardRow.toCard(kind: AlbumKind) = AlbumCard(
        key = "${kind.key}:$id",
        kind = kind,
        id = id,
        name = name,
        total = total,
        lastDate = lastDate,
        covers = listOf(cover0, cover1, cover2, cover3),
    )

    companion object {
        const val RECENT_COUNT = 4
        private const val DAY_MS = 86_400_000L
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** Start of the "Recently added" window: the last 30 days. */
        fun recentSince(): Long = System.currentTimeMillis() - 30 * DAY_MS
    }
}
