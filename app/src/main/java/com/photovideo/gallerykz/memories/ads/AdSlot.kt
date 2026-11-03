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
        /** A [large] slot is a native ad whenever the block has a native id, even when its type says banner. */
        fun of(screen: ScreenAd, key: String, large: Boolean = false): SlotSpec {
            val inter = AdRemoteConfig.config.inter
            return SlotSpec(
                show = screen.show, native = screen.native || large && screen.nativeId.isNotEmpty(), bannerId = screen.bannerId, nativeId = screen.nativeId, large = large,
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
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var loading = false
    private var destroyed = false
    private var resumed = false
    private var token = 0
    private var nativeAd: NativeAd? = null
    private var banner: AdView? = null
    private var loadedOnce = false
    private var placeholder: View? = null
    private var pulse: android.animation.ObjectAnimator? = null

    private val refresh = Runnable { if (!destroyed && resumed) request() }

    private val configListener: () -> Unit = {
        liveSpec?.let { provider -> if (!destroyed && !loading) reconfigure(provider()) }
    }

    init {
        owner.lifecycle.addObserver(this)
        if (liveSpec != null) AdRemoteConfig.addListener(configListener)
    }

    fun start() {
        if (destroyed) return
        request()
    }

    /** A new place of the same slot (the gallery page switches tabs): shows the other config. */
    fun reconfigure(newSpec: SlotSpec) {
        if (destroyed) return
        spec = newSpec
        loadedOnce = false
        handler.removeCallbacks(refresh)
        clearAds()
        request()
    }

    private fun request() {
        if (loading || destroyed) return
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
        if (!loadedOnce) showPlaceholder()
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
            GoogleAds.loadBanner(activity, id, widthDp) { view ->
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
        if (spec.refreshSeconds > 0 && resumed) handler.postDelayed(refresh, spec.refreshSeconds * 1000L)
    }

    /** A Google ad could not be had: the Quiz ad when the config says so, else the place goes away. */
    private fun failed() {
        loading = false
        removePlaceholder()
        if (AdRemoteConfig.config.googleFailedQuiz && showQuiz()) return
        if (!loadedOnce) hide()
    }

    private fun showQuiz(): Boolean {
        clearAds()
        val ok = QuizAds.bindCard(activity, container, spec.large || spec.native && spec.large)
        if (ok) {
            container.visibility = View.VISIBLE
            onVisibility(true)
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
        nativeAd = ad
        banner?.destroy()
        banner = null
        old?.destroy()
        container.visibility = View.VISIBLE
        onVisibility(true)
    }

    private fun showBanner(view: AdView) {
        removePlaceholder()
        val old = banner
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
        banner = view
        nativeAd?.destroy()
        nativeAd = null
        old?.destroy()
        container.visibility = View.VISIBLE
        onVisibility(true)
    }

    /** The skeleton of the ad (the skill's shimmer sizes) that pulses until the ad arrives, fails or the slot goes away. */
    private fun showPlaceholder() {
        if (placeholder != null || container.childCount > 0 && keepContentOnFail) return
        val layout = if (spec.native && spec.large) R.layout.ad_shimmer_large else R.layout.ad_shimmer_small
        val v = android.view.LayoutInflater.from(activity).inflate(layout, container, false)
        container.removeAllViews()
        container.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        scheduleRefresh()
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        banner?.pause()
        handler.removeCallbacks(refresh)
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
