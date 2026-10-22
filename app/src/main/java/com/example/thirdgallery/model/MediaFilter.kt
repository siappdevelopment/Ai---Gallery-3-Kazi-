package com.example.thirdgallery.model

import com.example.thirdgallery.database.MediaTypes

/** Gallery filter tabs (All / Photo / Video / Favorite) expressed as database query parameters. */
enum class MediaFilter(val types: List<Int>, val minFavorite: Int) {
    ALL(listOf(MediaTypes.IMAGE, MediaTypes.VIDEO), 0),
    PHOTOS(listOf(MediaTypes.IMAGE), 0),
    VIDEOS(listOf(MediaTypes.VIDEO), 0),
    FAVORITES(listOf(MediaTypes.IMAGE, MediaTypes.VIDEO), 1),
}
