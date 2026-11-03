package com.photovideo.gallerykz.memories.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.nativead.NativeAd
import com.photovideo.gallerykz.memories.R

/** What one slot shows: a banner or a native ad (small or large), and how it is kept fresh. */
class SlotSpec(
    val show: Boolean,
    val native: Boolean,
    val bannerId: String,
    val nativeId: String,
    val large: Boolean = false,
    val refreshSeconds: Int = 0,
    /** Keep the next ad ready (the config's native / banner "preload" type). */
    val preload: Boolean = false,
    val cacheKey: String = "",
) {
    companion object {
        fun of(screen: ScreenAd, key: String, large: Boolean = false): SlotSpec {
            val inter = AdRemoteConfig.config.inter
            return SlotSpec(
                show = screen.show, native = screen.native, bannerId = screen.bannerId, nativeId = screen.nativeId, large = large,
                refreshSeconds = if (screen.autoRefresh) screen.autoSeconds else 0,
                preload = if (screen.native) inter.nativePreload else inter.bannerPreload, cacheKey = key,
            )
        }
    }
}

/** Ads kept ready for the next slot that asks (the config's "preload" type). One ad per key. */
object SlotCache {
    private val natives = HashMap<String, NativeAd>()

    @Synchronized
    fun takeNative(key: String): NativeAd? = natives.remove(key)

    @Synchronized
    fun putNative(key: String, ad: NativeAd) {
        natives.put(key, ad)?.destroy()
    }

    fun prepareNative(context: Context, key: String, id: String) {
        if (key.isEmpty() || id.isEmpty() || !GoogleAds.canRequest(context) || AdRemoteConfig.config.priorityQuiz) return
        synchronized(this) { if (natives.containsKey(key)) return }
        GoogleAds.loadNative(context.applicationContext, id) { ad -> if (ad != null) putNative(key, ad) }
    }
}

/**
 * One ad place inside [container]: loads a Google ad (or the Quiz one when the priority or a failure says so), shows a quiet
 * placeholder while it loads, refreshes on the config's interval, and lets go of everything when the screen is destroyed.
 * Only one request is ever open; a failure with no Quiz fallback hides the place again.
 */
class AdSlot(
    private val activity: Activity,
    private val container: ViewGroup,
    owner: LifecycleOwner,
    private var spec: SlotSpec,
    /** Called when the place became visible or hidden (the screen may need to move its content). */
    private val onVisibility: (Boolean) -> Unit = {},
    /** Keep what was in the container (an existing placeholder) when no ad can be shown. */
    private val keepContentOnFail: Boolean = false,
    /** When given, the slot re-reads its spec whenever a new config arrives (the config may come after the screen opened). */
    private val liveSpec: (() -> SlotSpec)? = null,
    /** True while a big native ad is on screen (some screens show the system navigation bar only then). */
    private val onBigNative: (Boolean) -> Unit = {},
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var loading = false
    private var started = false
    private var destroyed = false
    private var resumed = false
    private var token = 0
    private var nativeAd: NativeAd? = null
    private var banner: AdView? = null
    private var loadedOnce = false
    private var placeholder: View? = null
    private var boxed = false
    private var pulse: android.animation.ObjectAnimator? = null

    /** True while the refresh timer runs; it goes on while the screen is paused or covered and is only reset by a finished load. */
    private var timerPending = false

    private val refresh = Runnable {
        timerPending = false
        if (!destroyed) request()
    }

    private val configListener: () -> Unit = {
        // A config that arrives while an ad is loading still wins when it asks for something else (the request in flight is dropped).
        liveSpec?.let { provider ->
            if (destroyed) return@let
            val next = provider()
            if (!loading || !sameAd(spec, next)) reconfigure(next)
        }
    }

    private fun sameAd(a: SlotSpec, b: SlotSpec) =
        a.show == b.show && a.native == b.native && a.large == b.large && a.bannerId == b.bannerId && a.nativeId == b.nativeId

    init {
        owner.lifecycle.addObserver(this)
        if (liveSpec != null) AdRemoteConfig.addListener(configListener)
    }

    fun start() {
        if (destroyed) return
        started = true
        request()
    }

    /** Loads the next ad now (a place that does not refresh on a timer asks for it when the user comes back to it). */
    fun refreshNow() {
        if (destroyed || !started || loading) return
        handler.removeCallbacks(refresh)
        timerPending = false
        request()
    }

    /** A new place of the same slot (the gallery page switches tabs): shows the other config. */
    fun reconfigure(newSpec: SlotSpec) {
        if (destroyed) return
        spec = newSpec
        loadedOnce = false
        loading = false
        token++
        handler.removeCallbacks(refresh)
        timerPending = false
        clearAds()
        request()
    }

    private fun request() {
        // Nothing is requested before the screen starts the slot (a config that arrives earlier only changes the spec).
        if (loading || destroyed || !started) return
        val context = activity.applicationContext
        if (!spec.show || !AdGate.adsAllowed(context)) {
            hide()
            return
        }
        if (AdRemoteConfig.config.priorityQuiz) {
            if (!showQuiz()) hide()
            return
        }
        val id = if (spec.native) spec.nativeId else spec.bannerId
        if (id.isEmpty() || !GoogleAds.canRequest(context)) {
            failed()
            return
        }
        loading = true
        val mine = ++token
        // The shimmer shows before every load, a refresh too: it takes the place of the old ad until the new one arrives.
        showPlaceholder()
        if (spec.native) {
            val cached = SlotCache.takeNative(spec.cacheKey)
            if (cached != null) {
                loading = false
                showNative(cached)
                afterLoad()
                return
            }
            GoogleAds.loadNative(context, id) { ad ->
                if (destroyed || mine != token) {
                    ad?.destroy()
                    return@loadNative
                }
                loading = false
                if (ad == null) failed() else {
                    showNative(ad)
                    afterLoad()
                }
            }
        } else {
            val widthDp = (container.resources.displayMetrics.widthPixels / container.resources.displayMetrics.density).toInt()
            GoogleAds.loadBanner(activity, id, widthDp, if (spec.large) (bigAreaPx() / activity.resources.displayMetrics.density).toInt() else 0) { view ->
                if (destroyed || mine != token) {
                    view?.destroy()
                    return@loadBanner
                }
                loading = false
                if (view == null) failed() else {
                    showBanner(view)
                    afterLoad()
                }
            }
        }
    }

    private fun afterLoad() {
        loadedOnce = true
        if (spec.native && spec.preload) SlotCache.prepareNative(activity.applicationContext, spec.cacheKey, spec.nativeId)
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        timerPending = false
        if (spec.refreshSeconds > 0 && !destroyed) {
            timerPending = true
            handler.postDelayed(refresh, spec.refreshSeconds * 1000L)
        }
    }

    /** A Google ad could not be had: the Quiz ad when the config says so, else the place goes away. */
    private fun failed() {
        loading = false
        removePlaceholder()
        if (AdRemoteConfig.config.googleFailedQuiz && showQuiz()) return
        // The shimmer replaced the old ad, so a failed refresh leaves nothing to keep: the place goes away.
        hide()
    }

    private fun gap(v: View) {
        // Bottom ads are full width with 2dp above and below.
        val px = (2 * activity.resources.displayMetrics.density).toInt()
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(0, px, 0, px)
            v.layoutParams = it
        }
    }

    private fun showQuiz(): Boolean {
        clearAds()
        val ok = QuizAds.bindCard(activity, container, large = spec.large, native = spec.native)
        if (ok) {
            container.getChildAt(0)?.let { gap(it) }
            container.visibility = View.VISIBLE
            onVisibility(true)
            onBigNative(spec.native && spec.large)
            loadedOnce = true
            scheduleRefresh()
        }
        return ok
    }

    private fun showNative(ad: NativeAd) {
        removePlaceholder()
        val view = NativeAdViews.inflate(activity, spec.large, container)
        NativeAdViews.populate(view, ad)
        val old = nativeAd
        container.removeAllViews()
        container.addView(view)
        gap(view)
        nativeAd = ad
        banner?.destroy()
        banner = null
        old?.destroy()
        container.visibility = View.VISIBLE
        onVisibility(true)
        onBigNative(spec.large)
    }

    /** The height of a big native ad (the skill's 250 sdp); a big banner gets a box of exactly this height. */
    private fun bigAreaPx(): Int = activity.resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._250sdp)

    private fun showBanner(view: AdView) {
        removePlaceholder()
        val old = banner
        container.removeAllViews()
        if (spec.large) {
            // Same area as the big native ad: the banner sits centred in it, never stretched.
            container.minimumHeight = bigAreaPx()
            container.setBackgroundResource(R.drawable.bg_ad_card)
            boxed = true
            container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        } else {
            container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
            gap(view)
        }
        banner = view
        nativeAd?.destroy()
        nativeAd = null
        old?.destroy()
        container.visibility = View.VISIBLE
        onVisibility(true)
        onBigNative(false)
    }

    /** The skeleton of the ad (the skill's shimmer sizes) that pulses until the ad arrives, fails or the slot goes away. */
    private fun showPlaceholder() {
        if (placeholder != null || container.childCount > 0 && keepContentOnFail) return
        val layout = if (spec.large) R.layout.ad_shimmer_large else R.layout.ad_shimmer_small
        val v = android.view.LayoutInflater.from(activity).inflate(layout, container, false)
        container.removeAllViews()
        container.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(v)
        placeholder = v
        pulse = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.35f).apply {
            duration = 700L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
        container.visibility = View.VISIBLE
        onVisibility(true)
    }

    private fun removePlaceholder() {
        pulse?.cancel()
        pulse = null
        placeholder?.let { if (it.parent === container) container.removeView(it) }
        placeholder = null
    }

    private fun clearAds() {
        onBigNative(false)
        if (boxed) {
            boxed = false
            container.minimumHeight = 0
            container.background = null
        }
        removePlaceholder()
        container.removeAllViews()
        nativeAd?.destroy()
        nativeAd = null
        banner?.destroy()
        banner = null
    }

    private fun hide() {
        loading = false
        if (keepContentOnFail) return
        clearAds()
        container.visibility = View.GONE
        onVisibility(false)
    }

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        banner?.resume()
        // The timer kept running while the screen was away; it only starts here when it is not running (a slot that loaded while paused).
        if (!timerPending && loadedOnce && !loading) scheduleRefresh()
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        banner?.pause()
        // The refresh timer is not stopped: the ad is refreshed on the configured interval also while the user is elsewhere.
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        AdRemoteConfig.removeListener(configListener)
        handler.removeCallbacksAndMessages(null)
        token++
        try {
            clearAds()
        } catch (e: Exception) {
            Log.d("KaziAds", "slot cleanup: $e")
        }
        owner.lifecycle.removeObserver(this)
    }
}
