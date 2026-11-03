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
        click = 0
        back = 0
        bottomNav = 0
        rightSwipe = 0
        leftSwipe = 0
        appTap = 0
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

    /** True when [screen] takes part in this kind (master flag and the screen's own flag). */
    fun counts(screen: AdScreen, back: Boolean): Boolean {
        val c = AdRemoteConfig.config
        val s = c.screen(screen)
        return if (back) c.inter.backShow && s.onBackInter && c.inter.backEvery > 0 else c.inter.clickShow && s.onClickInter && c.inter.clickEvery > 0
    }

    fun onClick(activity: Activity, screen: AdScreen, next: () -> Unit) = handle(activity, screen, false, next)

    fun onBack(activity: Activity, screen: AdScreen, next: () -> Unit) = handle(activity, screen, true, next)

    private fun handle(activity: Activity, screen: AdScreen, back: Boolean, next: () -> Unit) {
        if (!counts(screen, back) || !AdGate.adsAllowed(activity)) {
            next()
            return
        }
        val every = if (back) AdRemoteConfig.config.inter.backEvery else AdRemoteConfig.config.inter.clickEvery
        val count = if (back) ++AdCounters.back else ++AdCounters.click
        if (count < every) {
            next()
            return
        }
        if (back) AdCounters.back = 0 else AdCounters.click = 0
        present(activity, if (back) Kind.BACK else Kind.CLICK, next)
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
            GoogleAds.showInterstitial(activity, cached.ad) {
                if (isPreload(kind)) warm(activity.applicationContext, kind)
                done()
            }
            return
        }
        loadAndShow(activity, kind, done)
    }

    private fun loadAndShow(activity: Activity, kind: Kind, done: () -> Unit) {
        val id = unitId(kind)
        if (id.isEmpty() || !GoogleAds.canRequest(activity)) {
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
            if (AdRemoteConfig.config.googleFailBrowser) {
                QuizAds.openBrowser(activity)
                done()
            } else {
                QuizAds.showFull(activity, QuizKind.INTER) { done() }
            }
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
        synchronized(cache) {
            if (cache.containsKey(kind) || !loadingKinds.add(kind)) return
        }
        GoogleAds.loadInterstitial(context, unitId(kind)) { ad ->
            synchronized(cache) {
                loadingKinds.remove(kind)
                if (ad != null) cache[kind] = Cached(ad, System.currentTimeMillis())
            }
            if (onlyIfConfigured) Log.d(TAG, "preloaded ${kind.name}: ${ad != null}")
        }
    }
}
