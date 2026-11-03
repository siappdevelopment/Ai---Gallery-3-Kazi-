package com.photovideo.gallerykz.memories.ads

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.photovideo.gallerykz.memories.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The full-screen Google native ad (`Google_Native` / full native step of a sequence or a load-on-demand flow): one full-screen
 * dialog that first shows the loading page (`ad_shimmer_full`) and, when the ad arrives, swaps in the ad page (`ad_native_full`).
 * A failure or the 10 s timeout closes it; a failure then takes the Quiz fallback when the caller asks for it. [run] calls `finish`
 * exactly once: true when the ad was shown and closed.
 */
object FullNativeAd {

    private const val TAG = "KaziAds"
    private const val TIMEOUT_MS = 10_000L
    private val main = Handler(Looper.getMainLooper())

    fun run(activity: Activity, id: String, failedQuiz: Boolean, finish: (Boolean) -> Unit) {
        val once = AtomicBoolean(false)
        fun done(shown: Boolean) {
            if (once.compareAndSet(false, true)) finish(shown)
        }
        fun fallback() {
            if (!failedQuiz || activity.isFinishing || activity.isDestroyed) done(false) else QuizAds.showFallback(activity, QuizKind.NATIVE, ::done)
        }
        if (id.isEmpty() || !GoogleAds.canRequest(activity) || activity.isFinishing || activity.isDestroyed) {
            fallback()
            return
        }
        val dialog = try {
            Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                window?.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(activity, R.color.native_full_bg)))
            }
        } catch (e: Exception) {
            Log.d(TAG, "full native dialog failed: $e")
            fallback()
            return
        }
        var pulse: ObjectAnimator? = null
        fun closeQuietly() {
            pulse?.cancel()
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (e: Exception) {
                // The window is already gone.
            }
        }
        // 1. the loading page
        try {
            val shimmer = LayoutInflater.from(activity).inflate(R.layout.ad_shimmer_full, null)
            dialog.setContentView(shimmer)
            keepClearOfBars(shimmer)
            shimmer.findViewById<View>(R.id.shimmerFullContent)?.let { content ->
                pulse = ObjectAnimator.ofFloat(content, View.ALPHA, 1f, 0.4f).apply {
                    duration = 700L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            dialog.show()
        } catch (e: Exception) {
            Log.d(TAG, "full native loading page failed: $e")
            closeQuietly()
            fallback()
            return
        }
        // 2. load, with one outcome: the ad, a failure or the timeout
        val settled = AtomicBoolean(false)
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                closeQuietly()
                done(false)
            }
        }
        main.postDelayed(timeout, TIMEOUT_MS)
        GoogleAds.loadNative(activity.applicationContext, id) { ad ->
            if (!settled.compareAndSet(false, true)) {
                ad?.destroy()
                return@loadNative
            }
            main.removeCallbacks(timeout)
            when {
                ad == null -> {
                    closeQuietly()
                    fallback()
                }
                activity.isFinishing || activity.isDestroyed -> {
                    ad.destroy()
                    closeQuietly()
                    done(false)
                }
                else -> showAd(activity, dialog, ad, ::done) { pulse?.cancel() }
            }
        }
    }

    /** 3. the ad page replaces the loading page in the same dialog; closing it ends the flow. */
    private fun showAd(activity: Activity, dialog: Dialog, ad: NativeAd, done: (Boolean) -> Unit, stopLoading: () -> Unit) {
        try {
            val page = LayoutInflater.from(activity).inflate(R.layout.ad_native_full, null)
            populate(page, ad)
            stopLoading()
            dialog.setOnDismissListener {
                ad.destroy()
                done(true)
            }
            dialog.setContentView(page)
            keepClearOfBars(page)
            page.findViewById<View>(R.id.ad_close).setOnClickListener {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "full native page failed: $e")
            ad.destroy()
            stopLoading()
            try {
                dialog.dismiss()
            } catch (ignored: Exception) {
                // The window is already gone.
            }
            done(false)
        }
    }

    private fun populate(page: View, ad: NativeAd) {
        val adView = page.findViewById<NativeAdView>(R.id.ad_view)
        adView.mediaView = page.findViewById<MediaView>(R.id.ad_media)

        val headline = page.findViewById<TextView>(R.id.ad_headline)
        headline.text = ad.headline
        adView.headlineView = headline

        val body = page.findViewById<TextView>(R.id.ad_body)
        if (ad.body.isNullOrEmpty()) body.visibility = View.GONE else body.text = ad.body
        adView.bodyView = body

        val icon = page.findViewById<ImageView>(R.id.ad_app_icon)
        val drawable = ad.icon?.drawable
        if (drawable == null) icon.visibility = View.GONE else icon.setImageDrawable(drawable)
        adView.iconView = icon

        val cta = page.findViewById<TextView>(R.id.ad_call_to_action)
        if (ad.callToAction.isNullOrEmpty()) cta.visibility = View.GONE else cta.text = ad.callToAction
        adView.callToActionView = cta

        // Rating / store / price: each chip appears only when the ad has that asset; the row goes away when none does.
        val stars = page.findViewById<TextView>(R.id.ad_stars)
        val store = page.findViewById<TextView>(R.id.ad_store)
        val price = page.findViewById<TextView>(R.id.ad_price)
        var chips = 0
        ad.starRating?.let {
            stars.text = String.format(java.util.Locale.getDefault(), "★ %.1f", it)
            stars.visibility = View.VISIBLE
            adView.starRatingView = stars
            chips++
        }
        if (!ad.store.isNullOrEmpty()) {
            store.text = ad.store
            store.visibility = View.VISIBLE
            adView.storeView = store
            chips++
        }
        if (!ad.price.isNullOrEmpty()) {
            price.text = ad.price
            price.visibility = View.VISIBLE
            adView.priceView = price
            chips++
        }
        if (chips == 0) page.findViewById<View>(R.id.ad_chips).visibility = View.GONE

        adView.setNativeAd(ad)
    }

    /** The layouts are used as they are; the status bar, a cut-out and the navigation bar are kept clear by padding the root. */
    internal fun keepClearOfBars(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
