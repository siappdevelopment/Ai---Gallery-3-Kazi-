package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.HighlightDetail
import com.example.thirdgallery.model.HighlightKind
import com.example.thirdgallery.model.HighlightRow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One highlight opened from For you. The card travels in the intent (kind, period and the best uids); the media
 * shown are read here: a time window (recent, week, month, year) shows everything added in it, a "best of" card
 * shows exactly the items it picked. Items deleted or moved away since drop out when the screen reloads.
 */
class HighlightsDetailViewModel(
    application: Application,
    state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).mediaDao()
    private val loading = AtomicBoolean(false)

    /** The card this screen was opened for; null when the intent carried nothing usable. */
    val card: HighlightCard? = run {
        val kind = state.get<Int>(ARG_KIND)?.let { HighlightKind.entries.getOrNull(it) }
        val from = state.get<Long>(ARG_FROM) ?: 0L
        val to = state.get<Long>(ARG_TO) ?: 0L
        if (kind == null || to <= from) null else HighlightCard(kind, from, to, 0, state.get<LongArray>(ARG_UIDS)?.toList().orEmpty())
    }

    private val _detail = MutableLiveData<HighlightDetail>()
    val detail: LiveData<HighlightDetail> = _detail

    /** Reads the media on a background thread; a reload that is still running is not started twice. */
    fun load() {
        val card = card ?: return
        if (!loading.compareAndSet(false, true)) return
        worker.execute {
            try {
                _detail.postValue(build(card))
            } catch (e: Exception) {
                _detail.postValue(HighlightDetail(emptyList(), emptyList(), 0))
            } finally {
                loading.set(false)
            }
        }
    }

    private fun build(card: HighlightCard): HighlightDetail {
        val items: List<MediaEntity> = when (card.kind) {
            HighlightKind.BEST_MONTH, HighlightKind.BEST_YEAR, HighlightKind.BEST_DATE ->
                card.uids.chunked(CHUNK).flatMap { dao.byUids(it) }
            else -> dao.mediaAdded(card.fromMs, card.toMs)
        }
        if (items.isEmpty()) return HighlightDetail(emptyList(), emptyList(), 0)

        val sorted = items.sortedWith(compareByDescending<MediaEntity> { it.dateMs }.thenByDescending { it.uid })
        val present = sorted.map { it.uid }.toSet()
        // The collage of the card, as long as its items still exist; otherwise the newest ones.
        val covers = card.uids.filter { it in present }.take(COVERS).ifEmpty { sorted.take(COVERS).map { it.uid } }

        val rows = ArrayList<HighlightRow>(sorted.size + 8)
        rows.add(HighlightRow.Hero(card.copy(count = sorted.size, uids = covers), sorted.size))
        sorted.groupBy { it.dayKey }.forEach { (day, tiles) ->
            rows.add(HighlightRow.Header(day, tiles.size))
            tiles.forEach { rows.add(HighlightRow.Tile(it)) }
        }
        return HighlightDetail(rows, sorted.asReversed().map { it.uid }, sorted.size)
    }

    companion object {
        const val ARG_KIND = "highlight_kind"
        const val ARG_FROM = "highlight_from"
        const val ARG_TO = "highlight_to"
        const val ARG_UIDS = "highlight_uids"
        private const val COVERS = 3
        private const val CHUNK = 500
        private val worker = Executors.newSingleThreadExecutor()
    }
}
