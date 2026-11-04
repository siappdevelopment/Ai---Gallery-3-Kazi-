package com.photovideo.gallerykz.memories.ads

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.interstitial.InterstitialAd
import java.util.concurrent.atomic.AtomicBoolean

/** The in-memory counters of the interstitials and swipes; a new config resets them and warms the preload caches. */
object AdCounters {
    @Volatile var click = 0
    @Volatile var back = 0
    @Volatile var bottomNav = 0
    @Volatile var rightSwipe = 0
    @Volatile var leftSwipe = 0
    @Volatile var appTap = 0

    fun onConfigApplied(context: Context) {
        // The click / Back / bottom-nav counters keep counting through a new config (they live for the process); only their ads are renewed.
        rightSwipe = 0
        leftSwipe = 0
        appTap = 0
        ScreenInterAds.clearCaches()
        ScreenInterAds.preparePreload(context)
        SwipeAds.preparePreload(context)
        AppOpenAds.prepare(context)
        IpLocation.refresh(context)
    }
}

/**
 * The navigation interstitials: a click (opening something), a Back, and a bottom-navigation switch. A kind only counts when
 * the master flag (`inter_ads`) and the screen's own `on_click_inter_ads_show` / `on_back_inter_ads_show` are both on, and
 * the ad appears on every Nth counted action. QUIZ priority shows the Quiz ad instead; a Google failure falls back to the Quiz
 * ad only when `Google_Ad_Failed_Show_Quiz` is on. [next] always runs exactly once, whatever happens.
 */
object ScreenInterAds {

    private const val TAG = "KaziAds"
    private const val TIMEOUT_MS = 10_000L
    private const val EXPIRY_MS = 55L * 60 * 1000
    private val main = Handler(Looper.getMainLooper())

    private enum class Kind { CLICK, BACK, BOTTOM }

    private class Cached(val ad: InterstitialAd, val at: Long)

    private val cache = HashMap<Kind, Cached>()
    private val loadingKinds = HashSet<Kind>()

    /** Bumped when a new config empties the caches, so a load still in flight for the old config is dropped. */
    @Volatile private var generation = 0

    /** While one click / Back decision is open (its ad is loading or showing), a second one is dropped (index 0 = click, 1 = Back). */
    private val busyAt = LongArray(2)
    private const val BUSY_STALE_MS = 30_000L
    private const val FLOW = "KaziAdFlow"

    fun clearCaches() {
        synchronized(cache) {
            generation++
            cache.clear()
            loadingKinds.clear()
        }
    }

    /** True when [screen] takes part in this kind (master flag and the screen's own flag). */
    fun counts(screen: AdScreen, back: Boolean): Boolean {
        val c = AdRemoteConfig.config
        val s = c.screen(screen)
        return if (back) c.inter.backShow && s.onBackInter && c.inter.backEvery > 0 else c.inter.clickShow && s.onClickInter && c.inter.clickEvery > 0
    }

    fun onClick(activity: Activity, screen: AdScreen, next: () -> Unit) = handle(activity, screen, false, next)

    fun onBack(activity: Activity, screen: AdScreen, next: () -> Unit) = handle(activity, screen, true, next)

    private fun handle(activity: Activity, screen: AdScreen, back: Boolean, next: () -> Unit) {
        val label = if (back) "back" else "click"
        val inter = AdRemoteConfig.config.inter
        if (!counts(screen, back)) {
            val globalOn = if (back) inter.backShow else inter.clickShow
            val screenOn = AdRemoteConfig.config.screen(screen).let { if (back) it.onBackInter else it.onClickInter }
            if (globalOn && !screenOn) Log.d(FLOW, "$label inter: '${screen.obj}' not counted (screen flag off)")
            next()
            return
        }
        if (!AdGate.adsAllowed(activity)) {
            Log.d(FLOW, "$label inter: skipped, this place is blocked by the ads country lists")
            next()
            return
        }
        val every = if (back) inter.backEvery else inter.clickEvery
        val index = if (back) 1 else 0
        val now = android.os.SystemClock.elapsedRealtime()
        if (busyAt[index] != 0L && now - busyAt[index] < BUSY_STALE_MS) {
            // The ad decision of the first tap / Back is still open: this one is dropped (not counted, not requested twice).
            Log.d(FLOW, "$label inter: dropped, the previous one is still open")
            return
        }
        val count = if (back) ++AdCounters.back else ++AdCounters.click
        Log.d(FLOW, "$label inter: '${screen.obj}' counted $count / $every")
        if (count < every) {
            next()
            return
        }
        // The counter restarts when the threshold is reached, whether or not an ad can then be shown.
        if (back) AdCounters.back = 0 else AdCounters.click = 0
        busyAt[index] = now
        present(activity, if (back) Kind.BACK else Kind.CLICK) {
            busyAt[index] = 0L
            next()
        }
    }

    /** Bottom navigation (the gallery page's tabs): its own counter and unit. */
    fun onBottomNav(activity: Activity, next: () -> Unit) {
        val main = AdRemoteConfig.config.main
        if (!main.bottomNavShow || main.bottomNavEvery <= 0 || !AdGate.adsAllowed(activity)) {
            next()
            return
        }
        if (++AdCounters.bottomNav < main.bottomNavEvery) {
            next()
            return
        }
        AdCounters.bottomNav = 0
        present(activity, Kind.BOTTOM, next)
    }

    private fun unitId(kind: Kind): String {
        val c = AdRemoteConfig.config
        return when (kind) {
            Kind.BACK -> c.inter.backId
            Kind.BOTTOM -> c.main.bottomNavId
            Kind.CLICK -> c.inter.clickId
        }
    }

    private fun isPreload(kind: Kind): Boolean {
        val c = AdRemoteConfig.config
        return when (kind) {
            Kind.BACK -> c.inter.backPreload
            Kind.BOTTOM -> c.main.bottomNavPreload
            Kind.CLICK -> c.inter.clickPreload
        }
    }

    private fun present(activity: Activity, kind: Kind, next: () -> Unit) {
        val once = AtomicBoolean(false)
        val done = { if (once.compareAndSet(false, true)) next() }
        if (activity.isFinishing || activity.isDestroyed) {
            done()
            return
        }
        if (AdRemoteConfig.config.priorityQuiz) {
            QuizAds.showFull(activity, QuizKind.INTER) { done() }
            return
        }
        val cached = synchronized(cache) { cache.remove(kind) }
        if (cached != null && System.currentTimeMillis() - cached.at < EXPIRY_MS) {
            GoogleAds.showInterstitial(activity, cached.ad) { shown ->
                if (isPreload(kind)) warm(activity.applicationContext, kind)
                if (shown) done() else googleFailed(activity, done)
            }
            return
        }
        loadAndShow(activity, kind, done)
    }

    private fun loadAndShow(activity: Activity, kind: Kind, done: () -> Unit) {
        val id = unitId(kind)
        if (id.isEmpty()) {
            Log.d(FLOW, "ad not requested: the unit id is empty in the Remote Config")
            googleFailed(activity, done)
            return
        }
        if (!GoogleAds.canRequest(activity)) {
            Log.d(FLOW, "ad not requested: the ads SDK is not started or there is no network")
            googleFailed(activity, done)
            return
        }
        val dialog: Dialog? = AdLoadingDialog.show(activity)
        val finished = AtomicBoolean(false)
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                AdLoadingDialog.dismiss(dialog)
                done()
            }
        }
        main.postDelayed(timeout, TIMEOUT_MS)
        GoogleAds.loadInterstitial(activity.applicationContext, id) { ad ->
            if (!finished.compareAndSet(false, true)) {
                return@loadInterstitial
            }
            main.removeCallbacks(timeout)
            AdLoadingDialog.dismiss(dialog)
            if (ad == null || activity.isFinishing || activity.isDestroyed) {
                if (ad == null) googleFailed(activity, done) else done()
                return@loadInterstitial
            }
            GoogleAds.showInterstitial(activity, ad) { shown ->
                if (isPreload(kind)) warm(activity.applicationContext, kind)
                if (shown) done() else googleFailed(activity, done)
            }
        }
    }

    private fun googleFailed(activity: Activity, done: () -> Unit) {
        if (AdRemoteConfig.config.googleFailedQuiz && !activity.isFinishing && !activity.isDestroyed) {
            QuizAds.showFallback(activity, QuizKind.INTER) { done() }
        } else {
            done()
        }
    }

    /** Keeps one interstitial per kind ready when the config says "preload". */
    fun preparePreload(context: Context) {
        for (kind in Kind.entries) warm(context.applicationContext, kind, onlyIfConfigured = true)
    }

    private fun warm(context: Context, kind: Kind, onlyIfConfigured: Boolean = false) {
        val c = AdRemoteConfig.config
        if (c.priorityQuiz || !isPreload(kind) || unitId(kind).isEmpty()) return
        val enabled = when (kind) {
            Kind.BACK -> c.inter.backShow && c.screens.values.any { it.onBackInter }
            Kind.CLICK -> c.inter.clickShow && c.screens.values.any { it.onClickInter }
            Kind.BOTTOM -> c.main.bottomNavShow
        }
        if (!enabled || !GoogleAds.canRequest(context)) return
        val gen: Int
        synchronized(cache) {
            if (cache.containsKey(kind) || !loadingKinds.add(kind)) return
            gen = generation
        }
        GoogleAds.loadInterstitial(context, unitId(kind)) { ad ->
            synchronized(cache) {
                // A load that finished after a new config emptied the caches belongs to the old config: dropped.
                if (gen != generation) return@loadInterstitial
                loadingKinds.remove(kind)
                if (ad != null) cache[kind] = Cached(ad, System.currentTimeMillis())
            }
            if (onlyIfConfigured) Log.d(TAG, "preloaded ${kind.name}: ${ad != null}")
        }
    }
}
