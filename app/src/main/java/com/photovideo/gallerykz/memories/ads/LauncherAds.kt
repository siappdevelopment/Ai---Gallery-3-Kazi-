package com.photovideo.gallerykz.memories.ads

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.photovideo.gallerykz.memories.events.EventLauncher
import com.photovideo.gallerykz.memories.events.EventPromptActivity
import com.photovideo.gallerykz.memories.launcher.LauncherNav

/** The ads of the onboarding screens: after the splash, after the default-app step, after Language and after the last intro page. */
object OnboardingAds {

    /** One full-screen ad (interstitial or app open); the Quiz version when the priority says so. [next] runs exactly once. */
    private fun fullScreen(activity: Activity, appOpen: Boolean, interId: String, next: () -> Unit) {
        val c = AdRemoteConfig.config
        if (!AdGate.adsAllowed(activity) || !AdGate.isNetworkAvailable(activity) && !c.priorityQuiz) {
            next()
            return
        }
        val type = when {
            c.priorityQuiz -> if (appOpen) SeqType.Q_APPOPEN else SeqType.Q_INTER
            appOpen -> SeqType.G_APPOPEN
            else -> SeqType.G_INTER
        }
        SequenceAds.run(activity, type, AdIds(interId, "", c.appOpenId), c.googleFailedQuiz) { next() }
    }

    fun afterSplash(activity: Activity, next: () -> Unit) {
        val s = AdRemoteConfig.config.splash
        if (!s.afterShow) next() else fullScreen(activity, s.afterAppOpen, s.afterInterId, next)
    }

    fun afterDefault(activity: Activity, next: () -> Unit) {
        val c = AdRemoteConfig.config
        if (!c.afterDefaultShow) next() else fullScreen(activity, c.afterDefaultAppOpen, c.onboardingInterId, next)
    }

    fun afterLanguage(activity: Activity, next: () -> Unit) {
        val c = AdRemoteConfig.config
        if (!c.languageInterShow) next() else fullScreen(activity, false, c.onboardingInterId, next)
    }

    fun afterIntro(activity: Activity, next: () -> Unit) {
        val c = AdRemoteConfig.config
        if (!c.introInterShow) next() else fullScreen(activity, false, c.onboardingInterId, next)
    }
}

/** The right / left swipe of the launcher (Home to the gallery, Home to the report): every Nth swipe, from the configured sequence. */
object SwipeAds {

    private val inters = HashMap<Boolean, InterstitialAd>()
    private val loading = HashSet<Boolean>()

    private fun cfg(right: Boolean) = if (right) AdRemoteConfig.config.main.right else AdRemoteConfig.config.main.left

    fun onSwipe(activity: Activity, right: Boolean) {
        val c = AdRemoteConfig.config
        val s = cfg(right)
        if (!s.show || s.steps.isEmpty() || !AdGate.adsAllowed(activity) || activity.isFinishing || activity.isDestroyed) return
        val count = if (right) ++AdCounters.rightSwipe else ++AdCounters.leftSwipe
        // 0 = every swipe; otherwise every Nth.
        if (s.every > 0 && count % s.every != 0) return
        val ids = AdIds(s.interId, s.nativeId, c.appOpenId)
        val key = if (right) "right_swipe" else "left_swipe"
        val type = SequenceAds.next(activity, key, s.steps, ids) ?: return
        val cached = if (type == SeqType.G_INTER && s.preload) synchronized(inters) { inters.remove(right) } else null
        if (cached != null) {
            GoogleAds.showInterstitial(activity, cached) { shown ->
                warm(activity.applicationContext, right)
                if (!shown && s.failedQuiz) QuizAds.showFallback(activity, QuizKind.INTER) { }
            }
            return
        }
        SequenceAds.run(activity, type, ids, s.failedQuiz) {
            if (type == SeqType.G_INTER && s.preload) warm(activity.applicationContext, right)
        }
    }

    fun preparePreload(context: Context) {
        warm(context, true)
        warm(context, false)
    }

    private fun warm(context: Context, right: Boolean) {
        val s = cfg(right)
        if (!s.show || !s.preload || s.interId.isEmpty() || s.steps.none { it.type == SeqType.G_INTER } || !GoogleAds.canRequest(context)) return
        synchronized(inters) { if (inters.containsKey(right) || !loading.add(right)) return }
        GoogleAds.loadInterstitial(context.applicationContext, s.interId) { ad ->
            synchronized(inters) {
                loading.remove(right)
                if (ad != null) inters[right] = ad
            }
        }
    }
}

/** The resume App Open ad: shown when the app comes back to the front, within `App_Open_Show_Per_Day`, only for the default Home app. */
object AppOpenAds {

    private const val EXPIRY_MS = 4L * 60 * 60 * 1000
    private var cached: AppOpenAd? = null
    private var cachedAt = 0L
    private var loading = false
    private var started = 0
    private var showing = false
    private var lastLoadFailed = false

    private fun skip(activity: Activity): Boolean =
        activity is EventPromptActivity || activity.javaClass.simpleName in SKIPPED

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0 && !skip(activity)) showIfAvailable(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started--
                // Warm the next one for the return.
                if (started == 0) prepare(activity.applicationContext)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun enabled(context: Context): Boolean {
        val c = AdRemoteConfig.config
        return c.appOpenShow && c.appOpenId.isNotEmpty() && !c.priorityQuiz && AdGate.adsAllowed(context) &&
            AdGate.intervalElapsed(context, "app_open", c.appOpenPerDay, zeroIsAlways = true)
    }

    fun prepare(context: Context) {
        val app = context.applicationContext
        if (cached != null || loading || !enabled(app) || !GoogleAds.canRequest(app)) return
        loading = true
        GoogleAds.loadAppOpen(app, AdRemoteConfig.config.appOpenId) { ad ->
            loading = false
            lastLoadFailed = ad == null
            if (ad != null) {
                cached = ad
                cachedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun showIfAvailable(activity: Activity) {
        if (showing || !LauncherNav.isHomeAppOrRoleHolder(activity) || !enabled(activity)) return
        val ad = cached
        if (ad == null || SystemClock.elapsedRealtime() - cachedAt > EXPIRY_MS) {
            cached = null
            val failed = ad == null && lastLoadFailed
            prepare(activity)
            if (failed) quizFallback(activity)
            return
        }
        cached = null
        showing = true
        GoogleAds.showAppOpen(activity, ad) { shown ->
            showing = false
            if (shown) AdGate.markShown(activity, "app_open") else quizFallback(activity)
            prepare(activity)
        }
    }

    /** The Google App Open ad failed (load or show): the Quiz App Open ad when `Google_Ad_Failed_Show_Quiz` says so. */
    private fun quizFallback(activity: Activity) {
        if (showing || !AdRemoteConfig.config.googleFailedQuiz || !QuizAds.available() || activity.isFinishing || activity.isDestroyed) return
        showing = true
        QuizAds.showFallback(activity, QuizKind.APP_OPEN) { shown ->
            showing = false
            if (shown) AdGate.markShown(activity, "app_open")
        }
    }

    /** Screens where an App Open ad must not cover what is going on. */
    private val SKIPPED = setOf(
        "LauncherHomeActivity", "SplashActivity", "LanguageActivity", "DefaultAppActivity", "Intro1Activity", "Intro2Activity",
        "Intro3Activity", "Intro4Activity", "MediaPermissionActivity", "MediaDeniteActivity", "NotiPermissionActivity",
        "CallEndActivity", "AdHostActivity", "DefaultHomeGuideActivity", "OverlayPermissionGuideActivity", "VideoPlayerActivity",
    )
}

/** The launcher's app-tap ad (every Nth tap on an app) and return ad (every Nth return from an app started here). */
object LauncherAds {

    private const val FILE = "launcher_ads"
    private var waitingForReturn = false
    private var launchedAt = 0L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** An app is about to be started from the launcher: the tap ad may come first, then [launch] runs (exactly once). */
    fun onAppTap(activity: Activity, launch: () -> Unit) {
        val c = AdRemoteConfig.config
        val tap = c.launcher.tap
        val run = {
            markExternalLaunch()
            launch()
        }
        if (!tap.show || tap.every <= 0 || tap.steps.isEmpty() || !AdGate.adsAllowed(activity) || activity.isFinishing || activity.isDestroyed) {
            run()
            return
        }
        if (++AdCounters.appTap < tap.every) {
            run()
            return
        }
        AdCounters.appTap = 0
        val ids = AdIds(tap.interId, tap.nativeId, c.appOpenId)
        val type = SequenceAds.next(activity, "launcher_tap", tap.steps, ids)
        if (type == null) {
            run()
            return
        }
        SequenceAds.run(activity, type, ids, c.googleFailedQuiz) { run() }
    }

    private fun markExternalLaunch() {
        waitingForReturn = true
        launchedAt = SystemClock.elapsedRealtime()
    }

    /** The launcher is back in front: after an external app, every Nth return shows the return ad. */
    fun onLauncherResumed(activity: Activity) {
        if (!waitingForReturn) return
        waitingForReturn = false
        if (SystemClock.elapsedRealtime() - launchedAt < 1_000L) return
        val c = AdRemoteConfig.config
        val back = c.launcher.back
        if (!back.show || back.every <= 0 || back.steps.isEmpty() || !AdGate.adsAllowed(activity)) return
        val count = prefs(activity).getInt("returns", 0) + 1
        prefs(activity).edit().putInt("returns", count).apply()
        if (count % back.every != 0) return
        val ids = AdIds(back.interId, back.nativeId, c.appOpenId)
        val type = SequenceAds.next(activity, "launcher_back", back.steps, ids) ?: return
        SequenceAds.run(activity, type, ids, c.googleFailedQuiz) { }
    }
}

/** The App Open ad after the phone is unlocked (`Unlock_App_Open_*`), shown in a transparent screen. */
object UnlockAds {

    private const val TAG = "KaziAds"
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent?.action != Intent.ACTION_USER_PRESENT) return
            val c = AdRemoteConfig.config
            val l = c.launcher
            if (!l.unlockShow || l.unlockSteps.isEmpty() || !AdGate.adsAllowed(context) || !AdGate.isNetworkAvailable(context)) return
            if (!AdGate.countryAllowed(context, l.unlockCountries, l.unlockExclude)) return
            if (!AdGate.intervalElapsed(context, "unlock_app_open", l.unlockPerDay, zeroIsAlways = true)) return
            val started = EventLauncher.startScreen(context, Intent(context, AdHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.d(TAG, "unlock ad screen started: $started")
        }
    }

    fun register(app: Application) {
        if (registered) return
        registered = true
        // A protected system broadcast: only a runtime receiver gets it, and only while the process lives.
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
