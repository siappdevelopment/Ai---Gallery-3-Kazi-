package com.example.thirdgallery.helper

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** The app's Light / Dark / System default choice (Settings > Theme), kept across restarts. */
object ThemePrefs {

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private const val FILE = "settings_prefs"
    private const val KEY_THEME = "theme_mode"

    fun get(context: Context): Int = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_THEME, SYSTEM)
            .takeIf { it in SYSTEM..DARK } ?: SYSTEM
    } catch (e: Exception) {
        SYSTEM
    }

    /** Saves the choice and applies it at once (visible screens are recreated by AppCompat). */
    fun set(context: Context, mode: Int) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_THEME, mode).apply()
        } catch (e: Exception) {
            // Best effort: the choice still applies for this session.
        }
        apply(mode)
    }

    /** Applies the saved choice; called once when the app starts. */
    fun applySaved(context: Context) = apply(get(context))

    private fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
}
