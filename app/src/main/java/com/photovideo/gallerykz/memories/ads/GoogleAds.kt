package com.photovideo.gallerykz.memories.ads

import com.photovideo.gallerykz.memories.R
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import java.util.concurrent.atomic.AtomicBoolean

/** Thin, crash-safe wrappers over the Google Mobile Ads SDK: every callback arrives exactly once and on the main thread. */
object GoogleAds {

    private const val TAG = "KaziAds"
    private val started = AtomicBoolean(false)

    /** Starts the SDK once, off the main thread. */
    fun init(app: android.app.Application) {
        if (!started.compareAndSet(false, true)) return
        Thread {
            try {
                MobileAds.initialize(app) { }
            } catch (e: Exception) {
                started.set(false)
                Log.w(TAG, "ads sdk did not start: $e")
            }
        }.start()
    }

    fun canRequest(context: Context): Boolean = started.get() && AdGate.isNetworkAvailable(context)

    fun loadBanner(activity: Activity, id: String, widthDp: Int, done: (AdView?) -> Unit) {
        val once = AtomicBoolean(false)
        try {
            val view = AdView(activity)
            view.adUnitId = id
            view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp.coerceAtLeast(320)))
            view.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    if (once.compareAndSet(false, true)) done(view)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.d(TAG, "banner failed: ${error.message}")
                    if (once.compareAndSet(false, true)) {
                        view.destroy()
                        done(null)
                    }
                }
            }
            view.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            if (once.compareAndSet(false, true)) done(null)
        }
    }

    fun loadNative(context: Context, id: String, done: (NativeAd?) -> Unit) {
        val once = AtomicBoolean(false)
        try {
            AdLoader.Builder(context, id)
                .forNativeAd { ad ->
                    if (once.compareAndSet(false, true)) done(ad) else ad.destroy()
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.d(TAG, "native failed: ${error.message}")
                        if (once.compareAndSet(false, true)) done(null)
                    }
                })
                .build()
                .loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            if (once.compareAndSet(false, true)) done(null)
        }
    }

    fun loadInterstitial(context: Context, id: String, done: (InterstitialAd?) -> Unit) {
        val once = AtomicBoolean(false)
        try {
            InterstitialAd.load(context, id, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    if (once.compareAndSet(false, true)) done(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.d(TAG, "interstitial failed: ${error.message}")
                    if (once.compareAndSet(false, true)) done(null)
                }
            })
        } catch (e: Exception) {
            if (once.compareAndSet(false, true)) done(null)
        }
    }

    fun loadAppOpen(context: Context, id: String, done: (AppOpenAd?) -> Unit) {
        val once = AtomicBoolean(false)
        try {
            AppOpenAd.load(context, id, AdRequest.Builder().build(), object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    if (once.compareAndSet(false, true)) done(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.d(TAG, "app open failed: ${error.message}")
                    if (once.compareAndSet(false, true)) done(null)
                }
            })
        } catch (e: Exception) {
            if (once.compareAndSet(false, true)) done(null)
        }
    }

    /** Shows [ad]; [done] gets true when it was shown and closed, false when it could not be shown. Exactly once. */
    fun showInterstitial(activity: Activity, ad: InterstitialAd, done: (Boolean) -> Unit) {
        val once = AtomicBoolean(false)
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (once.compareAndSet(false, true)) done(true)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                if (once.compareAndSet(false, true)) done(false)
            }
        }
        try {
            ad.show(activity)
        } catch (e: Exception) {
            if (once.compareAndSet(false, true)) done(false)
        }
    }

    fun showAppOpen(activity: Activity, ad: AppOpenAd, done: (Boolean) -> Unit) {
        val once = AtomicBoolean(false)
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (once.compareAndSet(false, true)) done(true)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                if (once.compareAndSet(false, true)) done(false)
            }
        }
        try {
            ad.show(activity)
        } catch (e: Exception) {
            if (once.compareAndSet(false, true)) done(false)
        }
    }
}

/** The "loading ad" spinner shown while a full-screen ad loads (never longer than the caller's timeout). */
object AdLoadingDialog {

    private var current: Dialog? = null

    /** The small centred "loading the ad" card; null when it cannot be shown (the flow then goes on without it). Only one at a time. */
    fun show(activity: Activity): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        dismiss(current)
        return try {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(R.layout.dialog_ad_loading)
            dialog.setCancelable(false)
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                window.setGravity(Gravity.CENTER)
                window.setDimAmount(0.6f)
            }
            dialog.show()
            current = dialog
            dialog
        } catch (e: Exception) {
            null
        }
    }

    fun dismiss(dialog: Dialog?) {
        if (dialog == null) return
        if (current === dialog) current = null
        try {
            if (dialog.isShowing) dialog.dismiss()
        } catch (e: Exception) {
            // The window is already gone.
        }
    }
}

internal fun parseAdColor(hex: String, fallback: Int): Int {
    val h = hex.trim().removePrefix("#")
    if (h.isEmpty()) return fallback
    return try {
        when (h.length) {
            6 -> Color.parseColor("#$h")
            8 -> Color.parseColor("#$h")
            else -> fallback
        }
    } catch (e: Exception) {
        fallback
    }
}

internal fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
