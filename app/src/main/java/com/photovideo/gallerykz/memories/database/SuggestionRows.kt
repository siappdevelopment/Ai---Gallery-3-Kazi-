package com.photovideo.gallerykz.memories.database

/** Just what finding groups of photos by time needs (a photo, when it was taken, in which folder). */
data class PhotoTime(val uid: Long, val dateMs: Long, val bucketId: Long)

/** The measured quality of one photo (see MediaQualityEntity). */
data class QualityScore(val uid: Long, val score: Int)
