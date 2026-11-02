package com.example.thirdgallery.repo

import android.content.Context
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.PhotoTime
import com.example.thirdgallery.helper.SuggestionPrefs
import com.example.thirdgallery.model.AlbumSuggestion
import com.example.thirdgallery.model.BurstGroup
import com.example.thirdgallery.model.FavoriteSuggestion
import com.example.thirdgallery.model.SuggestionSet

/**
 * Works out the three For you suggestions from the media index (no file is read, nothing is scanned again):
 *  - Add these to an album: the biggest group of photos from one short period (a trip, an event) that are in no
 *    album yet, spread evenly over the period when there are more than 18;
 *  - Best from bursts: runs of at least 3 photos taken within 2.5 seconds of each other in one folder, with the
 *    sharpest, best exposed shot (from the quality measurements) recommended;
 *  - Favorite these best shots: the highest scoring "Best photos" that are not favorites yet.
 * Dismissed suggestions are left out. All functions are blocking: call them off the main thread.
 */
class SuggestionsRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context.applicationContext)
    private val dao = db.mediaDao()

    fun load(): SuggestionSet = SuggestionSet(findAlbum(), findBursts(), findFavorites())

    /** Only the bursts (the Best from bursts screen). */
    fun loadBursts(): List<BurstGroup> = findBursts()

    // ---- Add these to an album ----

    private fun findAlbum(): AlbumSuggestion? {
        val dismissed = SuggestionPrefs.dismissed(context, SuggestionPrefs.KIND_ALBUM)
        val photos = dao.albumCandidates(ALBUM_SCAN_LIMIT).sortedBy { it.dateMs }
        val clusters = ArrayList<List<PhotoTime>>()
        var current = ArrayList<PhotoTime>()
        fun flush() {
            // A trip or an event: enough photos, over a few days at most.
            if (current.size >= ALBUM_MIN_PHOTOS && current.last().dateMs - current.first().dateMs <= ALBUM_MAX_SPAN_MS) {
                clusters.add(current)
            }
            current = ArrayList()
        }
        for (photo in photos) {
            if (current.isNotEmpty() && photo.dateMs - current.last().dateMs > ALBUM_GAP_MS) flush()
            current.add(photo)
        }
        flush()
        val best = clusters
            .filter { it.first().uid !in dismissed }
            .maxWithOrNull(compareBy<List<PhotoTime>> { it.size }.thenBy { it.last().dateMs }) ?: return null
        val chosen = spread(best, ALBUM_MAX_SHOWN)
        return AlbumSuggestion(
            uids = chosen.map { it.uid }.reversed(), // newest first
            fromMs = best.first().dateMs,
            toMs = best.last().dateMs,
            key = best.first().uid,
        )
    }

    /** At most [max] items, evenly spread over the list (all of them when there are no more than [max]). */
    private fun <T> spread(items: List<T>, max: Int): List<T> {
        if (items.size <= max) return items
        return List(max) { i -> items[i * (items.size - 1) / (max - 1)] }
    }

    // ---- Best from bursts ----

    private fun findBursts(): List<BurstGroup> {
        val photos = dao.photoTimes() // newest first
        val groups = ArrayList<List<PhotoTime>>()
        var current = ArrayList<PhotoTime>()
        fun flush() {
            if (current.size >= BURST_MIN_SHOTS && current.size <= BURST_MAX_SHOTS) groups.add(current)
            current = ArrayList()
        }
        for (photo in photos) {
            val last = current.lastOrNull()
            if (last != null && (last.bucketId != photo.bucketId || last.dateMs - photo.dateMs > BURST_GAP_MS)) flush()
            current.add(photo)
        }
        flush()
        val shown = groups.take(BURST_MAX_GROUPS)
        val scores = HashMap<Long, Int>()
        shown.flatten().map { it.uid }.chunked(SQL_CHUNK).forEach { chunk ->
            dao.qualityScores(chunk).forEach { scores[it.uid] = it.score }
        }
        return shown.map { group ->
            // The best measured shot; when nothing is measured yet, the one in the middle of the burst.
            val measured = group.filter { scores.containsKey(it.uid) }
            val best = measured.maxByOrNull { scores.getValue(it.uid) } ?: group[group.size / 2]
            BurstGroup(group.map { it.uid }, best.uid, group.first().dateMs)
        }
    }

    // ---- Favorite these best shots ----

    private fun findFavorites(): FavoriteSuggestion? {
        val dismissed = SuggestionPrefs.dismissed(context, SuggestionPrefs.KIND_FAVORITES)
        val uids = dao.favoriteCandidates(FAVORITE_CANDIDATES).filter { it !in dismissed }.take(FAVORITE_MAX_SHOWN)
        return if (uids.size >= FAVORITE_MIN_SHOWN) FavoriteSuggestion(uids) else null
    }

    fun dismissAlbum(key: Long) = SuggestionPrefs.dismiss(context, SuggestionPrefs.KIND_ALBUM, listOf(key))

    fun dismissFavorites(uids: Collection<Long>) = SuggestionPrefs.dismiss(context, SuggestionPrefs.KIND_FAVORITES, uids)

    private companion object {
        const val ALBUM_SCAN_LIMIT = 3000
        const val ALBUM_GAP_MS = 18L * 60 * 60 * 1000
        const val ALBUM_MAX_SPAN_MS = 7L * 24 * 60 * 60 * 1000
        const val ALBUM_MIN_PHOTOS = 6
        const val ALBUM_MAX_SHOWN = 18
        const val BURST_GAP_MS = 2500L
        const val BURST_MIN_SHOTS = 3
        const val BURST_MAX_SHOTS = 60
        const val BURST_MAX_GROUPS = 200
        const val FAVORITE_CANDIDATES = 120
        const val FAVORITE_MAX_SHOWN = 18
        const val FAVORITE_MIN_SHOWN = 6
        const val SQL_CHUNK = 500
    }
}
