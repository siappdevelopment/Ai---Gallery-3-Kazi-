package com.photovideo.gallerykz.memories.model

/**
 * How the Gallery grid is ordered. [orderBy] is a fixed SQL fragment (never user text); the uid tie-break keeps the
 * order stable. Only the date orders keep the "day" headers, because a name or size order mixes days.
 */
enum class GallerySort(val orderBy: String, val groupsByDay: Boolean) {
    DATE_NEWEST("dateMs DESC, uid DESC", true),
    DATE_OLDEST("dateMs ASC, uid ASC", true),
    NAME_AZ("displayName COLLATE NOCASE ASC, uid ASC", false),
    NAME_ZA("displayName COLLATE NOCASE DESC, uid DESC", false),
    SIZE_LARGEST("sizeBytes DESC, uid DESC", false),
    SIZE_SMALLEST("sizeBytes ASC, uid ASC", false),
}
