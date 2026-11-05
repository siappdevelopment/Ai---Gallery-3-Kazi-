package com.photovideo.gallerykz.memories.repo

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.photovideo.gallerykz.memories.database.AppDatabase
import com.photovideo.gallerykz.memories.database.PlaceScanProgress
import com.photovideo.gallerykz.memories.database.PlaceSummary
import com.photovideo.gallerykz.memories.helper.MemoryPrefs
import com.photovideo.gallerykz.memories.model.MediaFilter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The places found by [PlaceScanManager]: photos grouped by where they were taken. Everything stays on the device. */
class PlaceRepository(context: Context) {

    private val app = context.applicationContext
    private val database = AppDatabase.getInstance(app)
    private val dao = database.locationDao()

    /**
     * Every place, the one with most photos first, rebuilt in the background whenever photo locations, place names
     * or the media index change (several changes in a row give one rebuild). Active only while somebody observes it.
     */
    fun observePlaces(): LiveData<List<PlaceSummary>> = MediatorLiveData<List<PlaceSummary>>().also { result ->
        // Per observer, so every screen gets its own rebuilds.
        val dirty = AtomicBoolean(false)
        val rebuild = { _: Any? ->
            dirty.set(true)
            worker.execute {
                while (dirty.compareAndSet(true, false)) {
                    try {
                        // Location memories off: no place is listed (what was read stays stored).
                        result.postValue(if (MemoryPrefs.isLocationMemories(app)) dao.places() else emptyList())
                    } catch (e: Exception) {
                        // Keeps the list that is shown; the next change tries again.
                    }
                }
            }
        }
        result.addSource(dao.observeRowCount(), rebuild)
        result.addSource(dao.observeLocatedCount(), rebuild)
        result.addSource(dao.observeNameCount(), rebuild)
        result.addSource(database.mediaDao().observeCount(MediaFilter.ALL.types, 0), rebuild)
        result.addSource(MemoryPrefs.changes, rebuild)
    }

    fun observeProgress(): LiveData<PlaceScanProgress> = dao.observeProgress()

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
