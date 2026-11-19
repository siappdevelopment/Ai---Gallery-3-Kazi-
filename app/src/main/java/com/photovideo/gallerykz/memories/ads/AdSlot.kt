package com.photovideo.gallerykz.memories.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
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
    /** The medium native card (media on the left, texts on the right, button below), used inside the media grid. */
    val medium: Boolean = false,
    /** Own layouts for the native ad and its shimmer (0 = the standard ones of its size); the intro screens use their own medium design. */
    val nativeLayout: Int = 0,
    val shimmerLayout: Int = 0,
    /** A banner in a box of exactly this height (dp), full width: an inline adaptive banner sized to the available width, centred in the box (0 = the normal anchored banner). */
    val boxHeightDp: Int = 0,
) {
    companion object {
        fun of(screen: ScreenAd, key: String, large: Boolean = false, medium: Boolean = false): SlotSpec {
            val inter = AdRemoteConfig.config.inter
            return SlotSpec(
                show = screen.show, native = screen.native, bannerId = screen.bannerId, nativeId = screen.nativeId, large = large,
                refreshSeconds = if (screen.autoRefresh) screen.autoSeconds else 0,
                preload = if (screen.native) inter.nativePreload else inter.bannerPreload, cacheKey = key, medium = medium,
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
    /** The ad always uses the dark theme (its dark colours), whatever theme the user chose for the app. */
    private val darkAd: Boolean = false,
    /** This place never shows a Quiz ad: not by the Quiz priority and not as the fallback of a failed Google ad. */
    private val noQuiz: Boolean = false,
    /** A refresh (not only the first load) covers the ad that is on screen with the shimmer until the new ad arrives. */
    private val shimmerOnRefresh: Boolean = false,
) : DefaultLifecycleObserver {

    /** True while the shimmer sits over the old ad of a refresh (the old ad is kept, hidden, in case the refresh fails). */
    private var refreshOverlay = false

    /** What the ad views are inflated with: the activity, or its dark-theme twin when [darkAd]. */
    private val viewContext: Context by lazy {
        if (!darkAd) {
            activity
        } else {
            val config = android.content.res.Configuration(activity.resources.configuration)
            config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_YES
            val theme = activity.applicationInfo.theme
            android.view.ContextThemeWrapper(activity.createConfigurationContext(config), if (theme != 0) theme else android.R.style.Theme_DeviceDefault)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var loading = false
    private var started = false
    private var lastRequestAt = 0L
    private var destroyed = false
    private var resumed = false
    private var token = 0
    private var nativeAd: NativeAd? = null
    private var banner: AdView? = null
    private var loadedOnce = false

    /** The last request found no ad: the next tries run silently, so the hidden place does not flash its frame on every retry. */
    private var lastFailed = false

    /** True while the card on screen is a Quiz ad (it has no handle like the Google ads do). */
    private var quizShown = false
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

    /** True when a hold of the ads ([AdGate.hold]) took this place down: it asks again when the hold ends. */
    private var heldHidden = false

    private val holdListener: (Boolean) -> Unit = { held ->
        if (!destroyed && started) {
            if (held) {
                // Whatever is loading is dropped, the refresh timer stops and the place goes away until the permission flow is over.
                heldHidden = true
                token++
                loading = false
                handler.removeCallbacks(refresh)
                timerPending = false
                hide()
            } else if (heldHidden) {
                heldHidden = false
                request()
            }
        }
    }

    init {
        owner.lifecycle.addObserver(this)
        if (liveSpec != null) AdRemoteConfig.addListener(configListener)
        AdGate.addHoldListener(holdListener)
    }

    fun start() {
        if (destroyed) return
        started = true
        request()
    }

    /** Loads the next ad now (a place that does not refresh on a timer asks for it when the user comes back to it). */
    fun refreshNow(minAgeSeconds: Int = 0) {
        if (destroyed || !started || loading) return
        // Not before the interval has passed since the last request (a place that comes back early keeps its ad).
        if (minAgeSeconds > 0 && lastRequestAt != 0L && SystemClock.elapsedRealtime() - lastRequestAt < minAgeSeconds * 1000L) return
        handler.removeCallbacks(refresh)
        timerPending = false
        request()
    }

    /** A new place of the same slot (the gallery page switches tabs): shows the other config. */
    fun reconfigure(newSpec: SlotSpec) {
        if (destroyed) return
        spec = newSpec
        loadedOnce = false
        lastFailed = false
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
        if (AdGate.isHeld) {
            // A permission flow is on screen: no request, no refresh, nothing shown; the hold listener asks again when it ends.
            heldHidden = true
            hide()
            return
        }
        if (!spec.show || !AdGate.adsAllowed(context)) {
            hide()
            return
        }
        if (AdRemoteConfig.config.priorityQuiz && !noQuiz) {
            if (!showQuiz()) hide()
            return
        }
        val id = if (spec.native) spec.nativeId else spec.bannerId
        if (id.isEmpty() || !GoogleAds.canRequest(context)) {
            failed()
            return
        }
        loading = true
        lastRequestAt = SystemClock.elapsedRealtime()
        val mine = ++token
        // The shimmer shows before the first load. A refresh keeps the ad that is on screen until the new one arrives, so a refresh that
        // fails (no fill, too many requests) can never leave the place empty.
        if (!hasAdOnScreen() && !lastFailed) showPlaceholder()
        else if (shimmerOnRefresh && hasAdOnScreen()) showRefreshOverlay()
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
            val density = container.resources.displayMetrics.density
            // The banner is as wide as the place it is shown in (the screen may keep margins or padding around it), so its button is never cut off.
            fun load(availablePx: Int) {
                if (destroyed || mine != token) return
                val widthDp = (availablePx / density).toInt()
                GoogleAds.loadBanner(activity, id, widthDp, if (spec.large || spec.medium || spec.boxHeightDp > 0) (boxPx() / density).toInt() else 0) { view ->
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
            val ownWidth = container.width - container.paddingLeft - container.paddingRight
            if (ownWidth > 0) {
                load(ownWidth)
            } else if (lastFailed) {
                // The place is hidden after a failure and will not be laid out: use the screen width.
                load(container.resources.displayMetrics.widthPixels)
            } else {
                // Not measured yet (the place was hidden): wait for its first layout, which the placeholder above has asked for.
                container.doOnLayout {
                    val laidOut = it.width - it.paddingLeft - it.paddingRight
                    load(if (laidOut > 0) laidOut else it.resources.displayMetrics.widthPixels)
                }
            }
        }
    }

    private fun afterLoad() {
        loadedOnce = true
        lastFailed = false
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

    /** True when an ad (Google or Quiz) is on screen and no placeholder covers it. */
    private fun hasAdOnScreen(): Boolean = placeholder == null && (nativeAd != null || banner != null || quizShown)

    /** A Google ad could not be had: the Quiz ad when the config says so, else the place goes away. */
    private fun failed() {
        loading = false
        // The shimmer over the old ad goes away and the old ad shows again.
        if (refreshOverlay) {
            removePlaceholder()
            if (loadedOnce && hasAdOnScreen()) {
                scheduleRefresh()
                return
            }
        }
        // A refresh that fails leaves the ad that is already showing where it is and tries again on the next interval.
        if (loadedOnce && hasAdOnScreen()) {
            scheduleRefresh()
            return
        }
        removePlaceholder()
        if (!noQuiz && AdRemoteConfig.config.googleFailedQuiz && showQuiz()) return
        lastFailed = true
        hide()
        // A place that refreshes on a timer asks again later (a no-fill is usually short); without this it stayed gone for good.
        if (spec.refreshSeconds > 0 && !destroyed) scheduleRefresh()
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
        val ok = QuizAds.bindCard(activity, container, large = spec.large, native = spec.native, medium = spec.medium, inflateContext = viewContext)
        if (ok) {
            quizShown = true
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
        val view = NativeAdViews.inflate(viewContext, spec.large, container, spec.medium, spec.nativeLayout)
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

    /** The height of a medium native ad (media 125 + button 40 + the gaps = 195 sdp); a medium banner gets a box of exactly this height. */
    private fun mediumAreaPx(): Int = activity.resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._195sdp)

    /** The box a banner is shown in when it must be as big as the native ad of the place (big or medium). */
    private fun boxPx(): Int = when {
        spec.boxHeightDp > 0 -> (spec.boxHeightDp * activity.resources.displayMetrics.density).toInt()
        spec.large -> bigAreaPx()
        else -> mediumAreaPx()
    }

    private fun showBanner(view: AdView) {
        removePlaceholder()
        val old = banner
        container.removeAllViews()
        if (spec.large || spec.medium || spec.boxHeightDp > 0) {
            // Same area as the big / medium native ad: the banner sits centred in it, never stretched.
            container.minimumHeight = boxPx()
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
        val layout = when {
            spec.shimmerLayout != 0 -> spec.shimmerLayout
            spec.large -> R.layout.ad_shimmer_large
            spec.medium -> R.layout.ad_shimmer_medium
            else -> R.layout.ad_shimmer_small
        }
        val v = android.view.LayoutInflater.from(viewContext).inflate(layout, container, false)
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

    /** The shimmer covers the ad of the last load exactly (same size, no jump) while the refresh loads; that ad stays underneath, hidden. */
    private fun showRefreshOverlay() {
        if (placeholder != null) return
        val layout = when {
            spec.shimmerLayout != 0 -> spec.shimmerLayout
            spec.large -> R.layout.ad_shimmer_large
            spec.medium -> R.layout.ad_shimmer_medium
            else -> R.layout.ad_shimmer_small
        }
        val v = android.view.LayoutInflater.from(viewContext).inflate(layout, container, false)
        for (i in 0 until container.childCount) container.getChildAt(i).visibility = View.INVISIBLE
        container.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        gap(v)
        placeholder = v
        refreshOverlay = true
        pulse = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.35f).apply {
            duration = 700L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun removePlaceholder() {
        pulse?.cancel()
        pulse = null
        placeholder?.let { if (it.parent === container) container.removeView(it) }
        placeholder = null
        if (refreshOverlay) {
            refreshOverlay = false
            for (i in 0 until container.childCount) container.getChildAt(i).visibility = View.VISIBLE
        }
    }

    private fun clearAds() {
        quizShown = false
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
        AdGate.removeHoldListener(holdListener)
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
