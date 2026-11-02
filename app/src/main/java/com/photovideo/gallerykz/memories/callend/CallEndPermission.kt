package com.photovideo.gallerykz.memories.callend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** The phone permission the call summary needs, and the one-time bookkeeping of where it was already asked. */
object CallEndPermission {

    const val PERMISSION = Manifest.permission.READ_PHONE_STATE

    private const val FILE = "call_end"
    private const val KEY_ASKED_AT_SPLASH = "asked_at_splash"

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The splash asks only on the first start; later starts never show the dialog there. */
    fun wasAskedAtSplash(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ASKED_AT_SPLASH, false)
    } catch (e: Exception) {
        true
    }

    fun markAskedAtSplash(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED_AT_SPLASH, true).commit()
        } catch (e: Exception) {
            // Best effort.
        }
    }
}
