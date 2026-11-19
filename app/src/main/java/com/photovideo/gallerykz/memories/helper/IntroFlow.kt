package com.photovideo.gallerykz.memories.helper

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.photovideo.gallerykz.memories.ads.AdHooks
import com.photovideo.gallerykz.memories.ads.AdScreen
import com.photovideo.gallerykz.memories.ads.AdSlot
import com.photovideo.gallerykz.memories.ads.SlotSpec
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

    /** True when intro screen [index] (1-based) is the last one of the configured count: Get started and the medium native ad. */
    fun isLast(index: Int): Boolean = index >= count()

    /**
     * Fits screen [index] (1-based) to the configured count: exactly that many dots with the [index]th active; Next (under the banner ad)
     * on every screen but the last, Get started (top right, with the medium native ad) on the last one.
     */
    fun bind(activity: Activity, next: View, getStarted: View, topBar: View, dots: LinearLayout?, index: Int) {
        val total = count()
        val last = isLast(index)
        next.visibility = if (last) View.GONE else View.VISIBLE
        topBar.visibility = if (last) View.VISIBLE else View.GONE
        getStarted.visibility = View.VISIBLE
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

    private fun adScreenOf(index: Int): AdScreen = when (index) {
        1 -> AdScreen.INTRO1
        2 -> AdScreen.INTRO2
        3 -> AdScreen.INTRO3
        else -> AdScreen.INTRO4
    }

    /**
     * The ad of intro screen [index] (1-based), always from that screen's own Remote Config block (IntroScreen1..4): `IntroN_Ad_Show`
     * switches it (off = the area is hidden completely), `IntroN_Ad_Type` picks banner or native, and the id of that type
     * (`IntroN_Banner_Id` / `IntroN_Native_Id`) is the one requested. Only the size follows the position: the last screen's native ad is
     * the medium one (native_medium_ad_layouts / native_medium_ad_shimmer), the other screens' native ad is the small card; a banner
     * sits in a 100dp area above the Next button (anywhere else it is as tall as it is). One slot per screen, so one request; the slot
     * follows the config when it changes. The area stays hidden while the ad is off, has no id, fails or has not arrived yet.
     */
    fun attachAd(activity: AppCompatActivity, index: Int): AdSlot? {
        val container = AdHooks.adContainer(activity) ?: return null
        val last = isLast(index)
        val screen = adScreenOf(index)
        val density = activity.resources.displayMetrics.density
        fun size(native: Boolean) {
            container.layoutParams = container.layoutParams.apply {
                height = if (!last && !native) (100 * density).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
                // The ad of the last screen keeps 5dp clear of the screen edges and 2dp above it, and none below.
                if (last && this is ViewGroup.MarginLayoutParams) {
                    setMargins((5 * density).toInt(), (2 * density).toInt(), (5 * density).toInt(), 0)
                    // It is the last thing on the screen: the panel around it adds no space under it either.
                    (container.parent as? View)?.setPadding(0, 0, 0, 0)
                }
            }
        }
        val spec = {
            val s = AdRemoteConfig.config.screen(screen)
            size(s.native)
            if (s.native) {
                SlotSpec(
                    show = s.show && s.nativeId.isNotEmpty(), native = true, bannerId = "", nativeId = s.nativeId, medium = last,
                    cacheKey = "slot_${screen.name}",
                    nativeLayout = if (last) R.layout.native_medium_ad_layouts else 0,
                    shimmerLayout = if (last) R.layout.native_medium_ad_shimmer else 0,
                )
            } else {
                SlotSpec(show = s.show && s.bannerId.isNotEmpty(), native = false, bannerId = s.bannerId, nativeId = "", cacheKey = "slot_${screen.name}")
            }
        }
        return try {
            AdSlot(activity, container, activity, spec(), liveSpec = spec).also { it.start() }
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "ads for ${screen.name} could not be attached: $e")
            null
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
