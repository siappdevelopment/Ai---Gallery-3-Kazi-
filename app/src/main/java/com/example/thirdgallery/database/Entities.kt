package com.example.thirdgallery.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A month recap card. [photo1]..[photo3] are gallery photo indexes (see HomePhotos). */
@Entity(tableName = "highlights")
data class HighlightEntity(
    @PrimaryKey val id: Int,
    /** 0-based month (Calendar.JANUARY = 0). */
    val month: Int,
    val year: Int,
    val photoCount: Int,
    val photo1: Int,
    val photo2: Int,
    val photo3: Int,
)

@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val photoCount: Int,
    val photoIndex: Int,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val photoCount: Int,
    val photoIndex: Int,
)

/** [type] is one of the SuggestionType constants; the UI text comes from string resources. */
@Entity(tableName = "suggestions")
data class SuggestionEntity(
    @PrimaryKey val id: Int,
    val type: String,
    val count: Int,
    val photo1: Int,
    val photo2: Int,
    val photo3: Int,
)

object SuggestionType {
    const val ALBUM = "album"
    const val BURSTS = "bursts"
    const val FAVORITES = "favorites"
}
