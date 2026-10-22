package com.example.thirdgallery.repo

import android.content.Context
import androidx.lifecycle.LiveData
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.ForYouDao
import com.example.thirdgallery.database.HighlightEntity
import com.example.thirdgallery.database.PersonEntity
import com.example.thirdgallery.database.PlaceEntity
import com.example.thirdgallery.database.SuggestionEntity
import com.example.thirdgallery.database.SuggestionType
import java.util.Calendar
import java.util.concurrent.Executors

class ForYouRepository(context: Context) {

    private val database = AppDatabase.getInstance(context)
    private val dao: ForYouDao = database.forYouDao()

    val highlights: LiveData<List<HighlightEntity>> = dao.observeHighlights()
    val persons: LiveData<List<PersonEntity>> = dao.observePersons()
    val places: LiveData<List<PlaceEntity>> = dao.observePlaces()
    val suggestions: LiveData<List<SuggestionEntity>> = dao.observeSuggestions()

    /**
     * Fills the database with the starter content on first use. Runs on a single background
     * thread, so concurrent callers can never insert twice.
     */
    fun seedIfEmpty() {
        ioExecutor.execute {
            try {
                database.runInTransaction {
                    if (dao.highlightCount() == 0) {
                        dao.insertHighlights(SEED_HIGHLIGHTS)
                        dao.insertPersons(SEED_PERSONS)
                        dao.insertPlaces(SEED_PLACES)
                        dao.insertSuggestions(SEED_SUGGESTIONS)
                    }
                }
            } catch (e: Exception) {
                // The screen simply stays empty; the next launch tries again.
            }
        }
    }

    private companion object {
        val ioExecutor = Executors.newSingleThreadExecutor()

        val SEED_HIGHLIGHTS = listOf(
            HighlightEntity(1, Calendar.OCTOBER, 2026, 38, 3, 4, 5),
            HighlightEntity(2, Calendar.SEPTEMBER, 2026, 52, 11, 12, 13),
            HighlightEntity(3, Calendar.AUGUST, 2026, 41, 19, 20, 21),
            HighlightEntity(4, Calendar.JULY, 2026, 27, 27, 28, 29),
        )
        val SEED_PERSONS = listOf(
            PersonEntity(1, "Emma", 142, 3),
            PersonEntity(2, "Liam", 96, 12),
            PersonEntity(3, "Olivia", 58, 21),
            PersonEntity(4, "Noah", 44, 30),
            PersonEntity(5, "Ava", 31, 39),
            PersonEntity(6, "Mason", 22, 48),
        )
        val SEED_PLACES = listOf(
            PlaceEntity(1, "New York", 84, 7),
            PlaceEntity(2, "San Francisco", 61, 18),
            PlaceEntity(3, "Yosemite", 37, 29),
            PlaceEntity(4, "Miami", 29, 40),
            PlaceEntity(5, "Chicago", 22, 51),
            PlaceEntity(6, "Grand Canyon", 18, 62),
        )
        val SEED_SUGGESTIONS = listOf(
            SuggestionEntity(1, SuggestionType.ALBUM, 18, 15, 16, 17),
            SuggestionEntity(2, SuggestionType.BURSTS, 3, 12, 13, 14),
            SuggestionEntity(3, SuggestionType.FAVORITES, 18, 33, 34, 35),
        )
    }
}
