package com.example.thirdgallery.model

/** Gallery layouts offered by the layout picker; only [implemented] ones can be selected. */
enum class GalleryLayout(val key: String, val implemented: Boolean) {
    CLASSIC("classic", true),
    TIMELINE("timeline", true),
    MOSAIC("mosaic", false),
    JOURNAL("journal", false);

    companion object {
        fun fromKey(key: String?): GalleryLayout =
            entries.firstOrNull { it.key == key && it.implemented } ?: CLASSIC
    }
}
