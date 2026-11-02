package com.example.thirdgallery.helper

import android.content.Context
import android.content.Intent
import com.example.thirdgallery.activity.DefaultAppActivity
import com.example.thirdgallery.activity.Intro1Activity
import com.example.thirdgallery.activity.Intro2Activity
import com.example.thirdgallery.activity.Intro3Activity
import com.example.thirdgallery.activity.Intro4Activity
import com.example.thirdgallery.activity.LanguageActivity
import com.example.thirdgallery.activity.MediaDeniteActivity
import com.example.thirdgallery.activity.MediaPermissionActivity
import com.example.thirdgallery.activity.NotiPermissionActivity
import com.example.thirdgallery.launcher.LauncherHomeActivity

/** The one place that maps an onboarding step to its screen (the splash, the launcher's guard and the default-app step all use it). */
object OnboardingRouter {

    fun screenOf(step: Int): Class<*> = when (step) {
        OnboardingPrefs.STEP_INTRO1 -> Intro1Activity::class.java
        OnboardingPrefs.STEP_INTRO2 -> Intro2Activity::class.java
        OnboardingPrefs.STEP_INTRO3 -> Intro3Activity::class.java
        OnboardingPrefs.STEP_INTRO4 -> Intro4Activity::class.java
        OnboardingPrefs.STEP_MEDIA -> MediaPermissionActivity::class.java
        OnboardingPrefs.STEP_MEDIA_DENIED -> MediaDeniteActivity::class.java
        OnboardingPrefs.STEP_NOTI -> NotiPermissionActivity::class.java
        OnboardingPrefs.STEP_DEFAULT -> DefaultAppActivity::class.java
        OnboardingPrefs.STEP_DONE -> LauncherHomeActivity::class.java
        else -> LanguageActivity::class.java
    }

    /** The screen of [step] (the stored step by default). */
    fun intent(context: Context, step: Int = OnboardingPrefs.getStep(context)): Intent = Intent(context, screenOf(step))

    /**
     * Like [intent] but safe to start from anywhere, also when the screen is already open (the system can send the Home
     * intent while the default-app step is still running): it brings the existing screen forward instead of stacking a copy.
     */
    fun resume(context: Context, step: Int = OnboardingPrefs.getStep(context)): Intent =
        intent(context, step).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
