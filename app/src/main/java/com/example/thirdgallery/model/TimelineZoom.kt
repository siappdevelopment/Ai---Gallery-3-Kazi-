package com.example.thirdgallery.model

/** Zoom levels of the Timeline layout: Days (photo grid), Months (cards) and Years (cards). */
enum class TimelineZoom(val key: String) {
    YEARS("years"),
    MONTHS("months"),
    DAYS("days");

    companion object {
        fun fromKey(key: String?): TimelineZoom = entries.firstOrNull { it.key == key } ?: DAYS
    }
}
