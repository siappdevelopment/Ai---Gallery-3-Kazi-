package com.example.thirdgallery.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ForYouDao {

    @Query("SELECT * FROM highlights ORDER BY id")
    fun observeHighlights(): LiveData<List<HighlightEntity>>

    @Query("SELECT * FROM persons ORDER BY id")
    fun observePersons(): LiveData<List<PersonEntity>>

    @Query("SELECT * FROM places ORDER BY id")
    fun observePlaces(): LiveData<List<PlaceEntity>>

    @Query("SELECT * FROM suggestions ORDER BY id")
    fun observeSuggestions(): LiveData<List<SuggestionEntity>>

    @Query("SELECT COUNT(*) FROM highlights")
    fun highlightCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHighlights(items: List<HighlightEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPersons(items: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaces(items: List<PlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSuggestions(items: List<SuggestionEntity>)
}
