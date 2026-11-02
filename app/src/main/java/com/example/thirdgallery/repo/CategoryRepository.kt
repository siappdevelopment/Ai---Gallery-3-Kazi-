package com.example.thirdgallery.repo

import android.content.Context
import androidx.lifecycle.LiveData
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.QualityProgress

/**
 * The three "Made for you" categories of the For you screen: how many photos each has and which photos cover its
 * card. All of it is read live from Room, so it follows the scan as it goes (see [MediaQualityScanManager]).
 */
class CategoryRepository(context: Context) {

    private val db = AppDatabase.getInstance(context.applicationContext)
    private val dao = db.mediaDao()

    val bestCount: LiveData<Int> = dao.observeBestCount()

    /** The two best-scoring photos: the big and the small picture of the Best photos card. */
    val bestCovers: LiveData<List<Long>> = dao.observeBestCovers()

    val portraitCount: LiveData<Int> = dao.observePortraitCount()
    val portraitCovers: LiveData<List<Long>> = dao.observePortraitCovers()

    val editedCount: LiveData<Int> = dao.observeEditedCount(editedSince())
    val editedCovers: LiveData<List<Long>> = dao.observeEditedCovers(editedSince())

    val progress: LiveData<QualityProgress> = db.qualityDao().observeProgress()

    companion object {
        /** "Recently edited" looks back this far. */
        private const val EDITED_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

        fun editedSince(): Long = System.currentTimeMillis() - EDITED_WINDOW_MS
    }
}
