package com.photovideo.gallerykz.memories.helper

import android.content.Context
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.ScanProgress
import com.photovideo.gallerykz.memories.repo.FaceScanManager

/** The one-line state of the face scan, shown where the people would be. */
object PeopleText {

    /**
     * What to tell the user, or null when there is nothing to say (the scan is done and people are shown).
     * [hasPeople] = at least one person or group is listed.
     */
    fun status(context: Context, status: FaceScanManager.Status?, progress: ScanProgress?, hasPeople: Boolean): String? = when {
        status == FaceScanManager.Status.NO_ACCESS -> context.getString(R.string.people_no_access)
        status == FaceScanManager.Status.FAILED -> context.getString(R.string.people_failed)
        (status == FaceScanManager.Status.SCANNING || status == FaceScanManager.Status.PENDING) &&
            progress != null && progress.total > 0 && progress.scanned < progress.total ->
            context.getString(R.string.people_scanning, progress.scanned, progress.total)
        !hasPeople -> context.getString(R.string.people_none)
        else -> null
    }
}
