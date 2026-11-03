package com.photovideo.gallerykz.memories.helper

import android.content.Context

/**
 * Tracks how far the user got in the Splash -> (system Home app screen) -> Default app -> Language -> Privacy policy -> Media permission
 * (-> Denied) -> Notification permission -> Intro1..4 -> Main flow so a killed
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
    const val STEP_DEFAULT = 9

    // Privacy policy (after Language, before the permissions); appended so stored values keep their meaning.
    const val STEP_POLICY = 10

    internal const val FILE = "gallery_prefs"
    internal const val KEY_STEP = "onboarding_step"
    private const val KEY_LANGUAGE_SELECTED = "language_selected"
    private const val KEY_DEFAULT_SYSTEM_SHOWN = "default_system_shown"
    private const val KEY_POLICY_ACCEPTED = "policy_accepted"

    /**
     * Current step. When no step was ever stored, an install from before this feature that
     * already picked a language is treated as finished (it used to go straight to Main).
     */
    fun getStep(context: Context): Int = try {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_STEP)) {
            prefs.getInt(KEY_STEP, STEP_DEFAULT).takeIf { it in STEP_LANGUAGE..STEP_POLICY } ?: STEP_DEFAULT
        } else if (prefs.getBoolean(KEY_LANGUAGE_SELECTED, false)) {
            STEP_DONE
        } else {
            // A fresh install starts with the first screen of the configured flow (Show_Screen_Flow).
            OnboardingFlow.first().step
        }
    } catch (e: Exception) {
        STEP_DEFAULT
    }

    /** True once the privacy policy screen was agreed to (it is never shown again). */
    fun isPolicyAccepted(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_POLICY_ACCEPTED, false)
    } catch (e: Exception) {
        false
    }

    fun setPolicyAccepted(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_POLICY_ACCEPTED, true).commit()
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /** True once the system's Home app screen was shown at the start of the default-app step (never twice). */
    fun isDefaultSystemShown(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DEFAULT_SYSTEM_SHOWN, false)
    } catch (e: Exception) {
        true
    }

    fun setDefaultSystemShown(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEFAULT_SYSTEM_SHOWN, true).commit()
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /** Written synchronously so the step is on disk before the next screen starts. */
    fun setStep(context: Context, step: Int) {
        if (step !in STEP_LANGUAGE..STEP_POLICY) return
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
