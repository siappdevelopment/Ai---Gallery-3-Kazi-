package com.example.thirdgallery.helper

import android.content.Context

/** Persists whether the user already picked a language. Cleared together with app data. */
object LanguagePrefs {

    private const val FILE = "gallery_prefs"
    private const val KEY_SELECTED = "language_selected"
    private const val KEY_TAG = "language_tag"

    fun isLanguageSelected(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SELECTED, false)
    } catch (e: Exception) {
        false
    }

    fun getSavedTag(context: Context): String? = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_TAG, null)
    } catch (e: Exception) {
        null
    }

    /**
     * Saves the language and moves onboarding on to Intro1 in a single write, so a kill in
     * between can never leave "language chosen" without the matching step.
     * Written synchronously so it is on disk before the screen closes.
     */
    fun save(context: Context, tag: String) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SELECTED, true)
                .putString(KEY_TAG, tag)
                .putInt(OnboardingPrefs.KEY_STEP, OnboardingPrefs.STEP_INTRO1)
                .commit()
        } catch (e: Exception) {
            // Best effort; the locale is still applied for this session.
        }
    }
}
