package com.example.thirdgallery.callend

import android.content.Context

/** Whether the call summary screen is switched on (Settings). It is on by default (it still needs phone access); Settings switches it off. */
object CallEndPrefs {

    private const val FILE = "call_end"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_ENABLED, true)
    } catch (e: Exception) {
        true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (e: Exception) {
            // Nothing to keep; the switch stays as it was.
        }
    }
}
