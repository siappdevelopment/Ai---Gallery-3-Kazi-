package com.photovideo.gallerykz.memories.helper

import android.content.Context
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.PlaceScanProgress
import com.photovideo.gallerykz.memories.database.PlaceSummary
import com.photovideo.gallerykz.memories.repo.PlaceScanManager

/** The wording of places: a place's name and the one-line state of the location reading. */
object PlaceText {

    /** The place's name, or "Unknown location" while it has none (it is never invented). */
    fun name(context: Context, place: PlaceSummary): String =
        place.placeName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.places_unknown)

    /** What to tell the user, or null when there is nothing to say (places are shown and the reading is done). */
    fun status(
        context: Context,
        status: PlaceScanManager.Status?,
        canRead: Boolean,
        progress: PlaceScanProgress?,
        hasPlaces: Boolean,
    ): String? = when {
        !canRead -> context.getString(R.string.places_no_permission)
        status == PlaceScanManager.Status.FAILED -> context.getString(R.string.places_failed)
        (status == PlaceScanManager.Status.SCANNING || status == PlaceScanManager.Status.PENDING) &&
            progress != null && progress.total > 0 && progress.scanned < progress.total ->
            context.getString(R.string.places_scanning, progress.scanned, progress.total)
        !hasPlaces -> context.getString(R.string.places_none)
        else -> null
    }
}
