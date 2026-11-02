package com.photovideo.gallerykz.memories.model

import com.photovideo.gallerykz.memories.database.MediaEntity

/** What the Gallery grid shows: a day header or one media tile. */
sealed class MediaUiModel {
    data class DayHeader(val dayKey: Long) : MediaUiModel()
    data class Item(val media: MediaEntity) : MediaUiModel()
}
