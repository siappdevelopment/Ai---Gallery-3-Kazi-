package com.photovideo.gallerykz.memories.ads

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner

/** How a screen joins the ad system: one line after `setContentView`. */
object AdHooks {

    /**
     * Gives [screen] its bottom ad (below the screen's own content, moved up over the system bar) and its Back interstitial.
     * Nothing changes on a screen whose block is off, except that the Back hook stays ready for a later config.
     */
    fun install(activity: AppCompatActivity, screen: AdScreen, large: Boolean = false) {
        try {
            attachBottom(activity, screen, large)
            attachBackAd(activity, screen)
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "ads for ${screen.name} could not be attached: $e")
        }
    }

    /** The screen's content gets a container below it; the container is only visible while an ad is. */
    fun attachBottom(activity: AppCompatActivity, screen: AdScreen, large: Boolean = false): AdSlot? =
        attachBottom(activity, SlotSpec.of(AdRemoteConfig.config.screen(screen), "slot_${screen.name}", large))

    fun attachBottom(activity: AppCompatActivity, spec: SlotSpec): AdSlot? {
        if (!spec.show) return null
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val child = content.getChildAt(0) ?: return null
        content.removeView(child)
        val wrapper = LinearLayout(activity)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val container = FrameLayout(activity)
        container.visibility = View.GONE
        wrapper.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // While an ad is there it sits above the system navigation bar and the content above it does not add the bar again.
        ViewCompat.setOnApplyWindowInsetsListener(wrapper) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (container.visibility == View.VISIBLE) {
                container.setPadding(0, 0, 0, bars.bottom)
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(bars.left, bars.top, bars.right, 0))
                    .build()
            } else {
                container.setPadding(0, 0, 0, 0)
                insets
            }
        }
        val slot = AdSlot(activity, container, activity, spec, onVisibility = { wrapper.post { ViewCompat.requestApplyInsets(wrapper) } })
        slot.start()
        return slot
    }

    /** Puts a slot into an existing container of the layout (the reserved ad area of the onboarding screens). */
    fun attachInto(activity: Activity, container: ViewGroup, owner: LifecycleOwner, spec: SlotSpec, keepContentOnFail: Boolean = true): AdSlot? {
        if (!spec.show) return null
        val slot = AdSlot(activity, container, owner, spec, keepContentOnFail = keepContentOnFail)
        slot.start()
        return slot
    }

    /** The container with id `adContainer` of an onboarding layout, if the layout has one. */
    fun adContainer(activity: Activity): ViewGroup? {
        val id = activity.resources.getIdentifier("adContainer", "id", activity.packageName)
        return if (id != 0) activity.findViewById(id) else null
    }

    /** The reserved area loses its side margins (its own and its design frame's) so the ad is as wide as the screen. */
    private fun fullWidth(container: ViewGroup) {
        container.setPadding(0, 0, 0, 0)
        container.background = null
        (container.parent as? FrameLayout)?.let { if (it.paddingLeft > 0 || it.paddingRight > 0) it.setPadding(0, 0, 0, 0) }
        val lp = container.layoutParams
        if (lp is LinearLayout.LayoutParams) {
            // The weighted ad area of the permission screens: the ad is as tall as it is, the rest of the screen takes what is left.
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            container.layoutParams = lp
        }
    }

    /** Onboarding screens: the ad goes into the reserved area (large native). */
    fun attachOnboarding(activity: AppCompatActivity, screen: AdScreen) {
        try {
            val container = adContainer(activity) ?: return
            val spec = SlotSpec.of(AdRemoteConfig.config.screen(screen), "slot_${screen.name}", large = true)
            // A screen whose ad is off keeps its design placeholder; otherwise the ad takes the whole width and its own height.
            if (!spec.show) return
            fullWidth(container)
            attachInto(activity, container, activity, spec, keepContentOnFail = false)
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "ads for ${screen.name} could not be attached: $e")
        }
    }

    /**
     * Back: when this screen's back interstitial is due, the ad shows first and the Back goes on after it; otherwise the Back is
     * handled as if this hook was not there.
     */
    fun attachBackAd(activity: AppCompatActivity, screen: AdScreen) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val pass = {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
                if (!ScreenInterAds.counts(screen, back = true)) {
                    pass()
                    return
                }
                ScreenInterAds.onBack(activity, screen) { if (!activity.isFinishing && !activity.isDestroyed) pass() }
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, callback)
    }

    /** A click that opens something: the click interstitial may come first, then [open] runs. */
    fun onClick(activity: Activity, screen: AdScreen, open: () -> Unit) = ScreenInterAds.onClick(activity, screen, open)
}
