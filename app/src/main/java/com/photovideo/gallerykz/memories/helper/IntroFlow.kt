package com.photovideo.gallerykz.memories.helper

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import com.photovideo.gallerykz.memories.ads.OnboardingAds
import com.photovideo.gallerykz.memories.launcher.LauncherHomeActivity

/**
 * How many intro screens the onboarding shows: `Intro_Screen_Count` of the Remote Config (1 to 4). A missing, zero, negative or
 * too large value shows all four, as before. The last screen of the flow runs the intro interstitial and then opens the launcher.
 */
object IntroFlow {

    const val MAX = 4

    fun count(): Int = AdRemoteConfig.config.introCount.takeIf { it in 1..MAX } ?: MAX

    /**
     * Fits screen [index] (1-based) to the configured count: exactly that many dots with the [index]th active, and the Get started
     * label on the last screen (the layout's own label, Next, stays on the others).
     */
    fun bind(activity: Activity, button: TextView, dots: LinearLayout?, index: Int) {
        val total = count()
        if (index >= total) button.setText(R.string.intro4_get_started)
        dots ?: return
        val density = activity.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        dots.removeAllViews()
        for (i in 0 until total) {
            val active = i == index - 1
            val dot = View(activity)
            dot.setBackgroundResource(if (active) R.drawable.bg_intro_dot_active else R.drawable.bg_intro_dot_inactive)
            val lp = LinearLayout.LayoutParams(px(if (active) 24 else 7), px(7))
            if (i > 0) lp.marginStart = px(6)
            dots.addView(dot, lp)
        }
    }

    /** The next step after intro screen [index] (1-based): the next intro screen, or the end of the flow. */
    fun advance(activity: Activity, index: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (index >= count()) {
            complete(activity)
            return
        }
        val next = OnboardingPrefs.STEP_INTRO1 + index
        OnboardingPrefs.setStep(activity, next)
        activity.startActivity(Intent(activity, OnboardingRouter.screenOf(next)))
        activity.finish()
    }

    /** True (and the flow is completed) when screen [index] lies beyond the configured count, e.g. a saved step from an older config. */
    fun skipIfBeyond(activity: Activity, index: Int): Boolean {
        if (index <= count()) return false
        complete(activity)
        return true
    }

    /** The intro is done: the intro interstitial (when due), then the next screen of the configured flow (the launcher when it was the last). */
    fun complete(activity: Activity) {
        OnboardingAds.afterIntro(activity) {
            if (!activity.isFinishing && !activity.isDestroyed) {
                OnboardingFlow.advance(activity, OnboardingFlow.Screen.INTRO)
                activity.finish()
            }
        }
    }
}
