package com.photovideo.gallerykz.memories.ads

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.photovideo.gallerykz.memories.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.random.Random

enum class QuizKind { INTER, APP_OPEN, NATIVE }

/**
 * Quiz ads: self-promotion that looks like a banner / native / interstitial / app open ad and is built only from the config
 * lists. They replace Google ads when `Ad_Priority` is QUIZ and back them up when a Google ad fails and
 * `Google_Ad_Failed_Show_Quiz` is on. At most three items are used; every list is read with the same random index.
 */
object QuizAds {

    private const val TAG = "KaziAds"
    private val RATES = doubleArrayOf(4.1, 4.6, 4.9)
    private val USERS = arrayOf("50K+", "100K+", "500K+", "1M+")

    fun available(): Boolean = AdRemoteConfig.config.quiz.syncedCount > 0

    private fun pick(list: List<String>, index: Int): String = list.getOrNull(index) ?: list.firstOrNull().orEmpty()

    /** The link a Quiz ad opens: one of the list, never one of the excluded, and none where the proxy lists say so. */
    fun pickLink(context: Context): String {
        val proxy = AdRemoteConfig.config.proxy
        val city = IpLocation.city(context)
        val state = IpLocation.state(context)
        val country = IpLocation.country(context)
        fun hit(list: List<String>, value: String) = value.isNotEmpty() && list.any { it.equals(value, true) }
        if (hit(proxy.cities, city) || hit(proxy.states, state) || hit(proxy.countries, country)) return ""
        val links = proxy.links.filter { link -> proxy.excludeLinks.none { it.equals(link, true) } }
        return links.randomOrNull().orEmpty()
    }

    /** "Quiz browser": opens the link, no ad view. True when something was opened. */
    fun openBrowser(context: Context): Boolean {
        val link = pickLink(context)
        if (link.isEmpty()) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.d(TAG, "quiz link could not open: $e")
            false
        }
    }

    // ---- banner / native card inside a slot ----

    /** Fills [container] with a Quiz card (with media when [large]); false when the config has no Quiz data. */
    /** [large]: the big card (same shape as the big Google native); [native]: the native texts instead of the banner ones. */
    fun bindCard(context: Context, container: ViewGroup, large: Boolean, native: Boolean = large): Boolean {
        val quiz = AdRemoteConfig.config.quiz
        val count = quiz.syncedCount
        if (count == 0) return false
        val index = Random.nextInt(count)
        val view = LayoutInflater.from(context).inflate(if (large) R.layout.ad_quiz_card_large else R.layout.ad_quiz_card, container, false)
        val title = if (native) quiz.nativeTitle else quiz.bannerTitle
        val desc = if (native) quiz.nativeDesc else quiz.bannerDesc
        view.findViewById<TextView>(R.id.quizTitle).text = pick(title, index)
        view.findViewById<TextView>(R.id.quizDesc).text = pick(desc, index)
        val button = view.findViewById<TextView>(R.id.quizButton)
        button.text = quiz.buttonText.ifEmpty { context.getString(R.string.ad_quiz_install) }
        loadImage(view.findViewById(R.id.quizIcon), pick(quiz.appIcons, index))
        val media = view.findViewById<ImageView>(R.id.quizMedia)
        if (large) {
            // The big card always has its picture area: the media image, else the app icon.
            media.visibility = View.VISIBLE
            loadImage(media, pick(quiz.nativeMedia.ifEmpty { quiz.appIcons }, index))
        } else {
            media.visibility = View.GONE
        }
        applyColors(context, view.findViewById(R.id.quizLabel), button)
        bindTapOnly(view) { openBrowser(context) }
        container.removeAllViews()
        container.addView(view)
        return true
    }

    // ---- full-screen Quiz ads ----

    /**
     * Shows a full-screen Quiz ad; [done] runs exactly once when it is closed, with true when it was shown (false = no Quiz
     * data or the screen is gone, so the caller should just continue).
     */
    fun showFull(activity: Activity, kind: QuizKind, done: (Boolean) -> Unit) {
        val quiz = AdRemoteConfig.config.quiz
        val count = quiz.syncedCount
        val once = AtomicBoolean(false)
        fun finish(shown: Boolean) {
            if (once.compareAndSet(false, true)) done(shown)
        }
        if (count == 0 || activity.isFinishing || activity.isDestroyed) {
            finish(false)
            return
        }
        val index = Random.nextInt(count)
        try {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_quiz_full, null)
            dialog.setContentView(view)
            dialog.setCancelable(false)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
            val titles = if (kind == QuizKind.NATIVE) quiz.nativeTitle else quiz.interTitle
            val descs = if (kind == QuizKind.NATIVE) quiz.nativeDesc else quiz.interDesc
            val media = when (kind) {
                QuizKind.INTER -> quiz.interMedia
                QuizKind.APP_OPEN -> quiz.appOpenMedia
                QuizKind.NATIVE -> quiz.nativeMedia
            }
            view.findViewById<TextView>(R.id.quizTitle).text = pick(titles, index)
            view.findViewById<TextView>(R.id.quizDesc).text = pick(descs, index)
            val rating = RATES[index % RATES.size]
            view.findViewById<TextView>(R.id.quizRating).text =
                activity.getString(R.string.ad_quiz_rating, rating, USERS[index % USERS.size])
            val button = view.findViewById<TextView>(R.id.quizButton)
            button.text = quiz.buttonText.ifEmpty { activity.getString(R.string.ad_quiz_install) }
            loadImage(view.findViewById(R.id.quizIcon), pick(quiz.appIcons, index))
            loadImage(view.findViewById(R.id.quizMedia), pick(media, index))
            applyColors(activity, view.findViewById(R.id.quizFullLabel), button)
            fun close() {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
            view.findViewById<View>(R.id.quizClose).setOnClickListener { close() }
            button.setOnClickListener {
                openBrowser(activity)
                close()
            }
            view.findViewById<View>(R.id.quizFullRoot).setOnClickListener {
                openBrowser(activity)
                close()
            }
            dialog.setOnDismissListener { finish(true) }
            dialog.show()
        } catch (e: Exception) {
            Log.d(TAG, "quiz dialog failed: $e")
            finish(false)
        }
    }

    // ---- shared helpers ----

    private fun loadImage(view: ImageView, url: String) {
        if (url.isEmpty()) {
            view.setImageResource(R.drawable.ic_splash_logo)
            return
        }
        try {
            Glide.with(view).load(url).placeholder(R.drawable.ic_splash_logo).error(R.drawable.ic_splash_logo).into(view)
        } catch (e: Exception) {
            view.setImageResource(R.drawable.ic_splash_logo)
        }
    }

    /** The Ad label and the button take the colours of the config (the button colour per theme). */
    internal fun applyColors(context: Context, label: View, button: View) {
        val config = AdRemoteConfig.config
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val labelColor = parseAdColor(config.labelColor, 0)
        if (labelColor != 0) label.backgroundTintList = ColorStateList.valueOf(labelColor)
        val buttonColor = parseAdColor(if (night) config.buttonDark else config.buttonLight, 0)
        if (buttonColor != 0) button.backgroundTintList = ColorStateList.valueOf(buttonColor)
    }

    /** A tap opens the link, a swipe (a finger that moved) does not: the card may sit inside scrolling content. */
    private fun bindTapOnly(root: View, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(root.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    v.performClick()
                    onTap()
                }
            }
            true
        }
    }
}

/** Fills a [NativeAdView] from a Google native ad and tints its label and button like the Quiz ads. */
object NativeAdViews {

    fun inflate(context: Context, large: Boolean, parent: ViewGroup): NativeAdView =
        LayoutInflater.from(context).inflate(if (large) R.layout.ad_native_large else R.layout.ad_native_small, parent, false) as NativeAdView

    fun populate(view: NativeAdView, ad: NativeAd) {
        val headline = view.findViewById<TextView>(R.id.adHeadline)
        val bodyView = view.findViewById<TextView>(R.id.adBody)
        val cta = view.findViewById<TextView>(R.id.adCta)
        val icon = view.findViewById<ImageView>(R.id.adIcon)
        val media = view.findViewById<MediaView?>(R.id.adMedia)
        headline.text = ad.headline
        view.headlineView = headline
        if (ad.body.isNullOrEmpty()) {
            bodyView.visibility = View.GONE
        } else {
            bodyView.text = ad.body
            bodyView.visibility = View.VISIBLE
            view.bodyView = bodyView
        }
        if (ad.callToAction.isNullOrEmpty()) {
            cta.visibility = View.INVISIBLE
        } else {
            cta.text = ad.callToAction
            cta.visibility = View.VISIBLE
            view.callToActionView = cta
        }
        val image = ad.icon
        if (image == null) {
            icon.visibility = View.GONE
        } else {
            icon.setImageDrawable(image.drawable)
            icon.visibility = View.VISIBLE
            view.iconView = icon
        }
        if (media != null) {
            view.mediaView = media
            media.visibility = if (ad.mediaContent != null) View.VISIBLE else View.GONE
        }
        QuizAds.applyColors(view.context, view.findViewById(R.id.adLabel), cta)
        view.setNativeAd(ad)
    }
}
