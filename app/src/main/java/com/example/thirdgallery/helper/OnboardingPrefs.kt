package com.example.thirdgallery.helper

import android.content.Context

/**
 * Tracks how far the user got in the Splash -> Language -> Intro1..4 -> Media permission
 * (-> Denied) -> Notification permission -> Main flow so a killed
 * app resumes on the same screen. Lives in the same file as [LanguagePrefs], so clearing app
 * data resets both.
 */
object OnboardingPrefs {

    const val STEP_LANGUAGE = 0
    const val STEP_INTRO1 = 1
    const val STEP_INTRO2 = 2
    const val STEP_INTRO3 = 3
    const val STEP_INTRO4 = 4
    const val STEP_DONE = 5

    // Permission flow, appended after DONE so values stored by earlier builds keep their meaning.
    const val STEP_MEDIA = 6
    const val STEP_MEDIA_DENIED = 7
    const val STEP_NOTI = 8

    internal const val FILE = "gallery_prefs"
    internal const val KEY_STEP = "onboarding_step"
    private const val KEY_LANGUAGE_SELECTED = "language_selected"

    /**
     * Current step. When no step was ever stored, an install from before this feature that
     * already picked a language is treated as finished (it used to go straight to Main).
     */
    fun getStep(context: Context): Int = try {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_STEP)) {
            prefs.getInt(KEY_STEP, STEP_LANGUAGE).takeIf { it in STEP_LANGUAGE..STEP_NOTI } ?: STEP_LANGUAGE
        } else if (prefs.getBoolean(KEY_LANGUAGE_SELECTED, false)) {
            STEP_DONE
        } else {
            STEP_LANGUAGE
        }
    } catch (e: Exception) {
        STEP_LANGUAGE
    }

    /** Written synchronously so the step is on disk before the next screen starts. */
    fun setStep(context: Context, step: Int) {
        if (step !in STEP_LANGUAGE..STEP_NOTI) return
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_STEP, step)
                .commit()
        } catch (e: Exception) {
            // Best effort; worst case the flow restarts from the previous stored step.
        }
    }
}
