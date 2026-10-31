package com.example.thirdgallery.model

import com.example.thirdgallery.database.MediaEntity

/** One row of the highlight detail list: the hero, a day heading, or one media tile. */
sealed class HighlightRow {
    data class Hero(val card: HighlightCard, val count: Int) : HighlightRow()
    data class Header(val dayKey: Long, val count: Int) : HighlightRow()
    data class Tile(val media: MediaEntity) : HighlightRow()
}

/**
 * Everything the highlight detail screen shows: [rows] (hero first, then the media grouped by day, newest day
 * first), and [slideshow] - the uids of the same media, oldest first, which the slideshow plays.
 */
data class HighlightDetail(
    val rows: List<HighlightRow>,
    val slideshow: List<Long>,
    val total: Int,
)
