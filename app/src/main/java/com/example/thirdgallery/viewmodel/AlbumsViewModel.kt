package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.database.AlbumCardRow
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.model.AlbumCard
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.AlbumSort
import com.example.thirdgallery.repo.AlbumRepository
import com.example.thirdgallery.repo.MediaRepository
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

        /** Start of the "Recently added" window: the last 30 days. */
        fun recentSince(): Long = System.currentTimeMillis() - 30 * DAY_MS
    }
}
