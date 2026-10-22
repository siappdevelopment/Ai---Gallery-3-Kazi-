package com.example.thirdgallery.model

import com.example.thirdgallery.database.MediaEntity

/** What the Mosaic grid shows (3 columns): day headers, a hero block per day, wide and normal tiles. */
sealed class MosaicUi {

    data class Header(val dayKey: Long, val place: String) : MosaicUi()

    /** Every non-header row belongs to a day and knows its media. */
    sealed class Content(open val media: MediaEntity) : MosaicUi()

    /** First item of a day: big tile (2 x 2) plus the next two items stacked beside it. */
    data class Hero(override val media: MediaEntity, val next1: Long?, val next2: Long?) : Content(media)

    /** Items 2 and 3 of a day are drawn inside the hero block; this row takes no space. */
    data class Spacer(override val media: MediaEntity) : Content(media)

    data class Tile(override val media: MediaEntity, val wide: Boolean) : Content(media)
}
