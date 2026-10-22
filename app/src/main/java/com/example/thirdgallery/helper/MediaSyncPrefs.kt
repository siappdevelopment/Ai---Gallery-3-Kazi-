package com.example.thirdgallery.helper

import android.content.Context

/** Remembers the MediaStore state of the last successful sync, so unchanged libraries skip it. */
object MediaSyncPrefs {

    private const val FILE = "gallery_prefs"
    private const val KEY_FINGERPRINT = "media_fingerprint"

    fun getFingerprint(context: Context): String? = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_FINGERPRINT, null)
    } catch (e: Exception) {
        null
    }

    fun setFingerprint(context: Context, value: String?) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().apply { if (value == null) remove(KEY_FINGERPRINT) else putString(KEY_FINGERPRINT, value) }
                .apply()
        } catch (e: Exception) {
            // Best effort: a missing fingerprint only means the next sync runs fully.
        }
    }
}
