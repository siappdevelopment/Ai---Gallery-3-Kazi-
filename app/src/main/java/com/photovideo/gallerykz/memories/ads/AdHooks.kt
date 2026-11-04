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
import com.photovideo.gallerykz.memories.helper.SystemNav

/** How a screen joins the ad system: one line after `setContentView`. */
object AdHooks {

    /**
     * Gives [screen] its bottom ad (below the screen's own content, moved up over the system bar) and its Back interstitial.
     * Nothing changes on a screen whose block is off, except that the Back hook stays ready for a later config.
     */
    fun install(activity: AppCompatActivity, screen: AdScreen, large: Boolean = false, navWithBigNative: Boolean = false, darkAd: Boolean = false) {
        // The screen counts its clicks (opening another screen) for the click interstitial through the base class.
        (activity as? AdAwareActivity)?.adScreen = screen
        try {
            val spec = { SlotSpec.of(AdRemoteConfig.config.screen(screen), "slot_${screen.name}", large) }
            attachBottom(activity, spec(), navWithBigNative, darkAd, liveSpec = spec)
            attachBackAd(activity, screen)
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "ads for ${screen.name} could not be attached: $e")
        }
    }

    /** The screen's content gets a container below it; the container is only visible while an ad is. */
    fun attachBottom(activity: AppCompatActivity, screen: AdScreen, large: Boolean = false): AdSlot? =
        attachBottom(activity, SlotSpec.of(AdRemoteConfig.config.screen(screen), "slot_${screen.name}", large))

    /** [navWithBigNative]: the system navigation bar is shown while the ad is a big native ad (else it stays hidden). */
    /** [liveSpec]: the place follows the config; a screen opened before the config arrived still gets its ad when it does. */
    fun attachBottom(
        activity: AppCompatActivity, spec: SlotSpec, navWithBigNative: Boolean = false, darkAd: Boolean = false,
        liveSpec: (() -> SlotSpec)? = null, noQuiz: Boolean = false,
    ): AdSlot? {
        if (!spec.show && (liveSpec == null || AdRemoteConfig.loaded)) return null
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val child = content.getChildAt(0) ?: return null
        content.removeView(child)
        val wrapper = LinearLayout(activity)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val container = FrameLayout(activity)
        container.visibility = View.GONE
        // A dark ad sits on a dark strip too (the viewer's own background), so no light band shows around it.
        if (darkAd) {
            // The strip takes the screen's own dark background (the screen's root colour), else the viewer's dark.
            val screenColor = (child.background as? android.graphics.drawable.ColorDrawable)?.color
            container.setBackgroundColor(screenColor ?: androidx.core.content.ContextCompat.getColor(activity, com.photovideo.gallerykz.memories.R.color.viewer_background))
        }
        wrapper.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // While an ad is there it sits above the system navigation bar and the content above it does not add the bar again.
        var hiddenByKeyboard = false
        ViewCompat.setOnApplyWindowInsetsListener(wrapper) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // With the keyboard open the ad steps aside, so a screen with a search field is not squeezed between the two.
            val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboard && container.visibility == View.VISIBLE) {
                hiddenByKeyboard = true
                container.visibility = View.GONE
            } else if (!keyboard && hiddenByKeyboard) {
                hiddenByKeyboard = false
                container.visibility = View.VISIBLE
                wrapper.post { ViewCompat.requestApplyInsets(wrapper) }
            }
            if (container.visibility == View.VISIBLE) {
                // 10dp above the ad, so it never touches the content (the media) over it.
                container.setPadding(0, (10 * activity.resources.displayMetrics.density).toInt(), 0, bars.bottom)
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(bars.left, bars.top, bars.right, 0))
                    .build()
            } else {
                container.setPadding(0, 0, 0, 0)
                insets
            }
        }
        val slot = AdSlot(
            activity, container, activity, spec,
            onVisibility = { wrapper.post { ViewCompat.requestApplyInsets(wrapper) } },
            onBigNative = { big -> if (navWithBigNative) SystemNav.setVisible(activity, big) },
            darkAd = darkAd,
            liveSpec = liveSpec,
            noQuiz = noQuiz,
        )
        slot.start()
        return slot
    }

    /** Puts a slot into an existing container of the layout (the reserved ad area of the onboarding screens). */
    fun attachInto(
        activity: Activity, container: ViewGroup, owner: LifecycleOwner, spec: SlotSpec,
        keepContentOnFail: Boolean = true, navWithBigNative: Boolean = false, onVisibility: (Boolean) -> Unit = {},
        autoStart: Boolean = true, noQuiz: Boolean = false,
    ): AdSlot? {
        if (!spec.show) return null
        val slot = AdSlot(
            activity, container, owner, spec, keepContentOnFail = keepContentOnFail, onVisibility = onVisibility,
            onBigNative = { big -> if (navWithBigNative) SystemNav.setVisible(activity, big) },
            noQuiz = noQuiz,
        )
        // autoStart = false: the screen starts the slot itself (when it is really in front); nothing is requested before.
        if (autoStart) slot.start()
        return slot
    }

    /** The container with id `adContainer` of an onboarding layout, if the layout has one. */
    fun adContainer(activity: Activity): ViewGroup? {
        val id = activity.resources.getIdentifier("adContainer", "id", activity.packageName)
        return if (id != 0) activity.findViewById(id) else null
    }

    /**
     * Takes the reserved ad area out of an onboarding screen and lets the rest use the space: the permission screens centre their
     * title, text and button in the whole sheet; the intro screens give the collage and the text the full height.
     */
    private fun collapseOnboardingAd(activity: Activity, container: ViewGroup) {
        try {
            val parent = container.parent as? ViewGroup ?: return
            if (parent is LinearLayout) {
                container.visibility = View.GONE
                val block = parent.getChildAt(0) as? LinearLayout ?: return
                block.gravity = android.view.Gravity.CENTER
                (block.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.height = 0
                    it.weight = 1f
                    block.layoutParams = it
                }
                for (i in 0 until block.childCount) {
                    val child = block.getChildAt(i)
                    if (child is android.widget.Space) {
                        (child.layoutParams as? LinearLayout.LayoutParams)?.let {
                            it.weight = 0f
                            it.height = (24 * activity.resources.displayMetrics.density).toInt()
                            child.layoutParams = it
                        }
                    }
                }
            } else {
                parent.visibility = View.GONE
                fun guide(name: String, percent: Float) {
                    val id = activity.resources.getIdentifier(name, "id", activity.packageName)
                    val g = if (id != 0) activity.findViewById<View>(id) else null
                    val lp = g?.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
                    lp.guidePercent = percent
                    g.layoutParams = lp
                }
                // Design: collage 0-30%, text 30-60%, ad 60-100%; without the ad: collage 0-50%, text 50-100%.
                guide("guideCollageEnd", 0.5f)
                guide("guideTextEnd", 1f)
            }
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "ad area could not be collapsed: $e")
        }
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
    fun attachOnboarding(activity: AppCompatActivity, screen: AdScreen, autoStart: Boolean = true): AdSlot? {
        try {
            val container = adContainer(activity) ?: return null
            val spec = SlotSpec.of(AdRemoteConfig.config.screen(screen), "slot_${screen.name}", large = true)
            // A screen whose ad is off keeps its design placeholder; otherwise the ad takes the whole width and its own height.
            if (!spec.show) {
                // Ads are off for this screen: no reserved area, no placeholder, the screen uses the space.
                collapseOnboardingAd(activity, container)
                return null
            }
            fullWidth(container)
            // When no ad can be had either (failure, no fill), the area goes away in the same way.
            return attachInto(
                activity, container, activity, spec, keepContentOnFail = false,
                onVisibility = { visible -> if (!visible) collapseOnboardingAd(activity, container) },
                autoStart = autoStart,
            )
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "ads for ${screen.name} could not be attached: $e")
            return null
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

    /**
     * Back of a pager page / fragment: while [owner] is resumed (the visible page) the system Back first runs [ad] (given the activity and
     * the continuation, which it must call exactly once), then [proceed]; without [proceed] the Back goes on to the
     * next handler as if this one was not there. Only one Back runs at a time.
     */
    fun fragmentBack(
        activity: androidx.fragment.app.FragmentActivity,
        owner: LifecycleOwner,
        ad: (Activity, () -> Unit) -> Unit,
        proceed: (() -> Unit)? = null,
    ): OnBackPressedCallback {
        var busy = false
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (busy) return
                fun pass() {
                    if (proceed != null) {
                        proceed()
                    } else {
                        isEnabled = false
                        activity.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
                busy = true
                ad(activity) {
                    busy = false
                    if (!activity.isFinishing && !activity.isDestroyed) pass()
                }
            }
        }
        owner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                callback.isEnabled = true
            }

            override fun onPause(owner: LifecycleOwner) {
                callback.isEnabled = false
            }
        })
        activity.onBackPressedDispatcher.addCallback(owner, callback)
        return callback
    }

    /** A click that opens something: the click interstitial may come first, then [open] runs. */
    fun onClick(activity: Activity, screen: AdScreen, open: () -> Unit) = ScreenInterAds.onClick(activity, screen, open)
}
