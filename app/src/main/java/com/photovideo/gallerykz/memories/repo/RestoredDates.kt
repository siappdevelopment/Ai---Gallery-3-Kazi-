package com.photovideo.gallerykz.memories.repo

import android.content.Context

/**
 * The original date of photos that came back from the vault. MediaStore gives the restored file a new item whose taken date is empty
 * for a photo without a date inside (and refuses to write the old one back), so the gallery would sort it by the day it was
 * restored. The date is remembered here by the new item's uid and used by the gallery whenever MediaStore has no taken date.
 */
object RestoredDates {

    private const val PREFS = "restored_dates"

    fun put(context: Context, uid: Long, dateMs: Long) {
        if (dateMs <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(uid.toString(), dateMs).apply()
    }

    /** Every remembered date by uid (read once per library scan). */
    fun all(context: Context): Map<Long, Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            val uid = key.toLongOrNull()
            val date = value as? Long
            if (uid != null && date != null) uid to date else null
        }.toMap()
}
