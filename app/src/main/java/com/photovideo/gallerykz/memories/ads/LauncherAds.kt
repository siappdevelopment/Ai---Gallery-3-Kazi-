package com.photovideo.gallerykz.memories.ads

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
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

    fun onSwipe(activity: Activity, right: Boolean) = perform(activity, right) { }

    /**
     * Coming back to Home from the gallery page (right = true, the way the right swipe went) or from the cleaning report
     * (right = false): the same swipe ad with the same counter, sequence and rules; [next] (the navigation) runs exactly once
     * after the ad, or at once when no ad is due.
     */
    fun onReturn(activity: Activity, right: Boolean, next: () -> Unit) = perform(activity, right, next)

    private fun perform(activity: Activity, right: Boolean, next: () -> Unit) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = { if (once.compareAndSet(false, true)) next() }
        val c = AdRemoteConfig.config
        val s = cfg(right)
        if (!s.show || s.steps.isEmpty() || !AdGate.adsAllowed(activity) || activity.isFinishing || activity.isDestroyed) {
            done()
            return
        }
        val count = if (right) ++AdCounters.rightSwipe else ++AdCounters.leftSwipe
        // 0 = every time; otherwise every Nth.
        if (s.every > 0 && count % s.every != 0) {
            done()
            return
        }
        val ids = AdIds(s.interId, s.nativeId, c.appOpenId)
        val key = if (right) "right_swipe" else "left_swipe"
        val type = SequenceAds.next(activity, key, s.steps, ids)
        if (type == null) {
            done()
            return
        }
        val cached = if (type == SeqType.G_INTER && s.preload) synchronized(inters) { inters.remove(right) } else null
        if (cached != null) {
            GoogleAds.showInterstitial(activity, cached) { shown ->
                warm(activity.applicationContext, right)
                if (!shown && s.failedQuiz) QuizAds.showFallback(activity, QuizKind.INTER) { done() } else done()
            }
            return
        }
        SequenceAds.run(activity, type, ids, s.failedQuiz) {
            if (type == SeqType.G_INTER && s.preload) warm(activity.applicationContext, right)
            done()
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
        "CallEndActivity", "PrivacyPolicyActivity", "AdHostActivity", "DefaultHomeGuideActivity", "OverlayPermissionGuideActivity", "VideoPlayerActivity",
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

/**
 * The App Open ad after the phone is unlocked (`Unlock_App_Open_*`), shown ON the launcher Home screen (skill `unlock_ad_logic`).
 *
 * It runs only while this app is the Home app. Nothing is started from the background (no overlay permission, no host screen):
 * after an unlock the system brings the launcher Home to the front and the ad is shown on it. The phone locking (`SCREEN_OFF`) arms
 * it and, when the gates pass, loads the next Google step ahead (`Unlock_App_Open_Ads_type = preload`). The unlock (`USER_PRESENT`,
 * the keyguard disappearing, or Home resuming with the keyguard gone) shows it as soon as Home is in front, within a 6 s window.
 * Log lines start with `Unlock ad:` (tag KaziAds).
 */
object UnlockAds {

    private const val TAG = "KaziAds"
    private const val KEY = "unlock_app_open"
    private const val WINDOW_MS = 6_000L
    private const val POLL_MS = 300L
    private const val WATCH_MS = 60_000L
    private const val CACHE_MAX_AGE_MS = 55L * 60 * 1000

    private val handler = Handler(Looper.getMainLooper())
    private var app: Application? = null
    private var registered = false
    private var armed = false
    private var unlockedAt = 0L
    private var screenOnAt = 0L
    private var home: java.lang.ref.WeakReference<Activity>? = null
    private var showing = false

    // The ad loaded ahead for the step the next unlock will use.
    private var cachedType: SeqType? = null
    private var cachedAd: Any? = null
    private var cachedAt = 0L
    private var loading = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Unlock ad: broadcast ${intent?.action}")
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onLocked()
                Intent.ACTION_SCREEN_ON -> watchKeyguard()
                Intent.ACTION_USER_PRESENT -> onUnlocked()
            }
        }
    }

    /** Registers for lock / unlock once; call from `Application.onCreate`. */
    fun register(application: Application) {
        if (registered) return
        registered = true
        app = application
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Protected system broadcasts: only a runtime receiver gets them, and only while the process lives.
        ContextCompat.registerReceiver(application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** The launcher Home is in front (call from its `onResume`); an unlock that just happened shows the ad now. */
    fun onHomeResumed(activity: Activity) {
        home = java.lang.ref.WeakReference(activity)
        Log.d(TAG, "Unlock ad: Home resumed, armed=$armed, keyguard locked=${keyguardLocked()}")
        // Some phones never send USER_PRESENT (no lock screen, face unlock, battery savers): Home back after a lock, with the keyguard gone, is the unlock.
        if (armed && unlockedAt == 0L && !keyguardLocked()) {
            Log.d(TAG, "Unlock ad: Home is back and the phone is unlocked (no USER_PRESENT needed)")
            unlockedAt = SystemClock.elapsedRealtime()
        }
        tryShow()
    }

    fun onHomePaused(activity: Activity) {
        if (home?.get() === activity) home = null
    }

    private fun keyguardLocked(): Boolean =
        (app?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    // ---- lock / unlock ----

    private val keyguardPoll = object : Runnable {
        override fun run() {
            if (!armed || unlockedAt != 0L) return
            if (!keyguardLocked()) {
                Log.d(TAG, "Unlock ad: the keyguard is gone")
                onUnlocked()
            } else if (SystemClock.elapsedRealtime() - screenOnAt < WATCH_MS) {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    private fun watchKeyguard() {
        if (!armed) return
        screenOnAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(keyguardPoll)
        handler.post(keyguardPoll)
    }

    private fun onLocked() {
        handler.removeCallbacks(keyguardPoll)
        val reason = whyNot()
        armed = reason == null
        unlockedAt = 0L
        Log.d(TAG, "Unlock ad: phone locked, armed=$armed (${reason ?: "ok"})")
        if (armed) preload()
    }

    private fun onUnlocked() {
        Log.d(TAG, "Unlock ad: phone unlocked, armed=$armed, home in front=${home?.get() != null}")
        if (!armed) return
        unlockedAt = SystemClock.elapsedRealtime()
        tryShow()
    }

    /** The unlock counts for a few seconds: Home resumes just before or just after the broadcast. */
    private fun tryShow() {
        if (!armed || unlockedAt == 0L) return
        if (SystemClock.elapsedRealtime() - unlockedAt > WINDOW_MS) {
            Log.d(TAG, "Unlock ad: the Home page did not come in front in time")
            armed = false
            return
        }
        val activity = home?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        armed = false
        unlockedAt = 0L
        show(activity)
    }

    // ---- the ad ----

    private fun ids(): AdIds {
        val c = AdRemoteConfig.config
        val l = c.launcher
        return AdIds(l.unlockInterId, l.unlockNativeId, l.unlockId.ifEmpty { c.appOpenId })
    }

    private fun show(activity: Activity) {
        val reason = whyNot()
        if (reason != null || showing) {
            Log.d(TAG, "Unlock ad: not shown (${reason ?: "an unlock ad is already showing"})")
            return
        }
        val l = AdRemoteConfig.config.launcher
        val ids = ids()
        // The slot time is written before the ad shows: a failed request also uses its slot.
        AdGate.markShown(activity, KEY)
        val type = SequenceAds.next(activity, KEY, l.unlockSteps, ids)
        if (type == null) {
            Log.d(TAG, "Unlock ad: no usable step in the sequence")
            return
        }
        showing = true
        Log.d(TAG, "Unlock ad: showing step $type (Unlock_App_Open_Per_Day = ${l.unlockPerDay})")
        val finish = { shown: Boolean ->
            showing = false
            Log.d(TAG, "Unlock ad: done, shown=$shown")
            preload()
        }
        when (val ready = takeCached(type)) {
            is AppOpenAd -> GoogleAds.showAppOpen(activity, ready) { shown ->
                if (!shown && l.unlockFailedQuiz) QuizAds.showFallback(activity, QuizKind.APP_OPEN, finish) else finish(shown)
            }
            is InterstitialAd -> GoogleAds.showInterstitial(activity, ready) { shown ->
                if (!shown && l.unlockFailedQuiz) QuizAds.showFallback(activity, QuizKind.INTER, finish) else finish(shown)
            }
            else -> SequenceAds.run(activity, type, ids, l.unlockFailedQuiz, finish)
        }
    }

    private fun takeCached(type: SeqType): Any? {
        val ad = cachedAd
        val fresh = cachedType == type && ad != null && SystemClock.elapsedRealtime() - cachedAt < CACHE_MAX_AGE_MS
        cachedAd = null
        cachedType = null
        return if (fresh) ad else null
    }

    /** `Unlock_App_Open_Ads_type = preload`: loads the Google ad of the step the next unlock will use (full native loads on demand). */
    private fun preload() {
        val context = app ?: return
        val l = AdRemoteConfig.config.launcher
        if (!l.unlockPreload || loading || !GoogleAds.canRequest(context)) return
        val ids = ids()
        val type = SequenceAds.next(context, KEY, l.unlockSteps, ids, advance = false) ?: return
        if (cachedType == type && cachedAd != null && SystemClock.elapsedRealtime() - cachedAt < CACHE_MAX_AGE_MS) return
        val store = { ad: Any? ->
            loading = false
            if (ad != null) {
                cachedAd = ad
                cachedType = type
                cachedAt = SystemClock.elapsedRealtime()
            }
            Log.d(TAG, "Unlock ad: preload $type ${if (ad != null) "ready" else "failed"}")
        }
        when (type) {
            SeqType.G_APPOPEN -> {
                loading = true
                GoogleAds.loadAppOpen(context, ids.appOpenId) { store(it) }
            }
            SeqType.G_INTER -> {
                loading = true
                GoogleAds.loadInterstitial(context, ids.interId) { store(it) }
            }
            else -> Unit
        }
    }

    // ---- gates ----

    /** Why the ad cannot run now (null = it can): switch, Home app, internet, own country lists, a usable step, the gap. */
    private fun whyNot(): String? {
        val context = app ?: return "not started"
        val l = AdRemoteConfig.config.launcher
        return when {
            !l.unlockShow -> "Unlock_App_Open_Show is false"
            l.unlockSteps.isEmpty() -> "Unlock_App_Open_Ad_Sequence is empty"
            !LauncherNav.isHomeAppOrRoleHolder(context) -> "this app is not the default Home app"
            !AdGate.isNetworkAvailable(context) -> "no internet"
            !AdGate.countryAllowed(context, l.unlockCountries, l.unlockExclude) -> "blocked by Unlock_Ad_Show_Country / Unlock_Ad_Show_Exclude_Location"
            SequenceAds.next(context, KEY, l.unlockSteps, ids(), advance = false) == null -> "no usable step (missing unit ids)"
            !AdGate.intervalElapsed(context, KEY, l.unlockPerDay, zeroIsAlways = true) -> "the gap since the last unlock ad (24 h / ${l.unlockPerDay}) is not over"
            else -> null
        }
    }
}
