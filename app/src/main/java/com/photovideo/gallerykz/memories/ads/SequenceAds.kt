package com.photovideo.gallerykz.memories.ads

import com.google.android.gms.ads.interstitial.InterstitialAd

import com.google.android.gms.ads.appopen.AppOpenAd

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import com.google.android.gms.ads.nativead.NativeAd
import com.photovideo.gallerykz.memories.R
import java.util.concurrent.atomic.AtomicBoolean

/** The units a sequence step may need. */
class AdIds(val interId: String = "", val nativeId: String = "", val appOpenId: String = "")

/**
 * Full-screen ads chosen from a configured sequence (swipes, app taps and returns, the lock screen, Recent / event / call
 * end back ads): `[type, count]` steps are used in turn, the position is kept on the phone, and a new sequence starts over.
 * Types: Google inter / app open / full-screen native, and Quiz inter / app open / native / browser. Sequences ignore the
 * priority flag; a Google failure falls back to the Quiz version when the caller says so. [run] calls `done` exactly once.
 */
object SequenceAds {

    private const val TIMEOUT_MS = 10_000L
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("ad_sequence", Context.MODE_PRIVATE)

    private fun usable(type: SeqType, ids: AdIds): Boolean = when (type) {
        SeqType.G_INTER -> ids.interId.isNotEmpty()
        SeqType.G_APPOPEN -> ids.appOpenId.isNotEmpty()
        SeqType.G_NATIVE -> ids.nativeId.isNotEmpty()
        else -> QuizAds.available() || type == SeqType.Q_BROWSER
    }

    /** The next type of the sequence (steps without a unit id are skipped); null when nothing can be shown. */
    @Synchronized
    fun next(context: Context, cursorKey: String, steps: List<SeqStep>, ids: AdIds, advance: Boolean = true): SeqType? {
        val flat = steps.flatMap { s -> List(s.count) { s.type } }
        if (flat.isEmpty()) return null
        val p = prefs(context)
        val signature = flat.joinToString(",") { it.name }
        var index = if (p.getString("${cursorKey}_sig", "") == signature) p.getInt("${cursorKey}_idx", 0) else 0
        var found: SeqType? = null
        var used = 0
        for (i in flat.indices) {
            val candidate = flat[(index + i) % flat.size]
            used = i + 1
            if (usable(candidate, ids)) {
                found = candidate
                break
            }
        }
        if (advance) p.edit().putString("${cursorKey}_sig", signature).putInt("${cursorKey}_idx", (index + used) % flat.size).apply()
        return found
    }

    /** Runs one step; `done(true)` when an ad (or the Quiz browser) was shown. */
    fun run(activity: Activity, type: SeqType, ids: AdIds, failedQuiz: Boolean, done: (Boolean) -> Unit) {
        val once = AtomicBoolean(false)
        val finish = { shown: Boolean -> if (once.compareAndSet(false, true)) done(shown) }
        if (activity.isFinishing || activity.isDestroyed) {
            finish(false)
            return
        }
        when (type) {
            SeqType.Q_BROWSER -> QuizBrowser.show(activity, finish)
            SeqType.Q_INTER -> QuizAds.showFull(activity, QuizKind.INTER, finish)
            SeqType.Q_APPOPEN -> QuizAds.showFull(activity, QuizKind.APP_OPEN, finish)
            SeqType.Q_NATIVE -> QuizAds.showFull(activity, QuizKind.NATIVE, finish)
            SeqType.G_INTER -> google<InterstitialAd>(activity, ids.interId, failedQuiz, QuizKind.INTER, finish,
                load = { id, cb -> GoogleAds.loadInterstitial(activity.applicationContext, id, cb) },
                show = { ad, cb -> GoogleAds.showInterstitial(activity, ad, cb) })
            SeqType.G_APPOPEN -> google<AppOpenAd>(activity, ids.appOpenId, failedQuiz, QuizKind.APP_OPEN, finish,
                load = { id, cb -> GoogleAds.loadAppOpen(activity.applicationContext, id, cb) },
                show = { ad, cb -> GoogleAds.showAppOpen(activity, ad, cb) })
            SeqType.G_NATIVE -> FullNativeAd.run(activity, ids.nativeId, failedQuiz, finish)
        }
    }

    private fun <T> google(
        activity: Activity,
        id: String,
        failedQuiz: Boolean,
        quizKind: QuizKind,
        finish: (Boolean) -> Unit,
        load: (String, (T?) -> Unit) -> Unit,
        show: (T, (Boolean) -> Unit) -> Unit,
    ) {
        fun fallback() {
            if (!failedQuiz || activity.isFinishing || activity.isDestroyed) {
                finish(false)
            } else {
                QuizAds.showFallback(activity, quizKind, finish)
            }
        }
        if (id.isEmpty() || !GoogleAds.canRequest(activity)) {
            fallback()
            return
        }
        val dialog: Dialog? = AdLoadingDialog.show(activity)
        val settled = AtomicBoolean(false)
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                AdLoadingDialog.dismiss(dialog)
                finish(false)
            }
        }
        main.postDelayed(timeout, TIMEOUT_MS)
        load(id) { ad ->
            if (!settled.compareAndSet(false, true)) return@load
            main.removeCallbacks(timeout)
            AdLoadingDialog.dismiss(dialog)
            when {
                ad == null -> fallback()
                activity.isFinishing || activity.isDestroyed -> finish(false)
                else -> show(ad) { shown -> if (shown) finish(true) else fallback() }
            }
        }
    }
}

/**
 * The back / Done ad of the event screens, the Recent page and the Call End screen: the next step of the screen's sequence,
 * allowed only when the config says so, the install-day gate passed, the "N per day" gap has elapsed and the country is allowed.
 */
object BackAds {

    /** [noQuiz]: only the Google steps of the sequence run, and a failed Google ad is not replaced by a Quiz ad or the Quiz browser. */
    fun show(activity: Activity, key: String, cfg: BackAdCfg, appOpenId: String, noQuiz: Boolean = false, next: () -> Unit) {
        val once = AtomicBoolean(false)
        val done = { if (once.compareAndSet(false, true)) next() }
        val config = AdRemoteConfig.config
        val steps = if (noQuiz) cfg.steps.filter { it.type == SeqType.G_INTER || it.type == SeqType.G_APPOPEN || it.type == SeqType.G_NATIVE } else cfg.steps
        // The Recent page, the Charging, Install / Uninstall and Call End screens match their state / country lists with the IP lookup when `App_Proxy_Check_Ip` is on.
        val ipLists = key == "recent" || key == "charging" || key == "install_uninstall" || key == "clend"
        val basic = cfg.show && steps.isNotEmpty() && AdGate.isNetworkAvailable(activity) && AdGate.adsAllowed(activity) &&
            cfg.afterDays > 0 && AdGate.daysSinceInstall(activity) >= cfg.afterDays &&
            AdGate.intervalElapsed(activity, "${key}_back", cfg.perDay, zeroIsAlways = false)
        val allowed = basic && if (ipLists) {
            AdGate.locationAllowed(activity, key, cfg.showCountries, cfg.excludeCountries, useIp = if (key == "clend") cfg.useIpCountry else config.proxy.checkIp)
        } else {
            AdGate.countryAllowed(activity, cfg.showCountries, cfg.excludeCountries, preferIp = cfg.useIpCountry)
        }
        if (ipLists) {
            android.util.Log.d(
                "KaziAds",
                "[$key] back ad decision: " + (if (allowed) "SHOW" else "HIDE") + " (show=${cfg.show} steps=${cfg.steps.size} afterDays=${cfg.afterDays} perDay=${cfg.perDay} basicGates=$basic)",
            )
        }
        if (!allowed || activity.isFinishing || activity.isDestroyed) {
            done()
            return
        }
        val ids = AdIds(cfg.interId, cfg.fullNativeId, appOpenId.ifEmpty { config.appOpenId })
        val type = SequenceAds.next(activity, "${key}_back", steps, ids)
        if (type == null) {
            done()
            return
        }
        SequenceAds.run(activity, type, ids, config.googleFailedQuiz && !noQuiz) { shown ->
            // The gap and the sequence move on only when an ad was really shown.
            if (shown) AdGate.markShown(activity, "${key}_back")
            done()
        }
    }
}
