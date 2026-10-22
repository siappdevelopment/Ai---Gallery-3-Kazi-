package com.example.thirdgallery.model

import com.example.thirdgallery.database.MediaEntity

/** What the Gallery grid shows: a day header or one media tile. */
sealed class MediaUiModel {
    data class DayHeader(val dayKey: Long) : MediaUiModel()
    data class Item(val media: MediaEntity) : MediaUiModel()
}
