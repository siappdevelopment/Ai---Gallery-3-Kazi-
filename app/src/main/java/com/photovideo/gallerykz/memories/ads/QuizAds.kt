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

    /**
     * The fallback of a failed Google full-screen ad (interstitial / app open; the caller has already checked
     * `Google_Ad_Failed_Show_Quiz`): `Google_ad_Fail_type = Browser` opens the Quiz link in the web view and shows no Quiz layout;
     * anything else (`Quiz`, missing, unknown) shows the Quiz ad layout of [kind]. [done] runs once, true when something showed.
     */
    fun showFallback(activity: Activity, kind: QuizKind, done: (Boolean) -> Unit) {
        if (AdRemoteConfig.config.googleFailBrowser) QuizBrowser.show(activity, done) else showFull(activity, kind, done)
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
        // A tap opens the Quiz link in the in-app web view (never an external browser).
        bindTapOnly(view) { (context as? Activity)?.let { QuizBrowser.show(it) { } } }
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
        if (kind == QuizKind.NATIVE) {
            showNativeFull(activity, index, ::finish)
            return
        }
        try {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val layout = when (kind) {
                QuizKind.INTER -> R.layout.dialog_quiz_inter
                QuizKind.APP_OPEN -> R.layout.dialog_quiz_app_open
                QuizKind.NATIVE -> R.layout.dialog_quiz_inter // never used: the native kind returns above
            }
            val view = LayoutInflater.from(activity).inflate(layout, null)
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
            fun close() {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
            // The app open layout has only the icon, the media and the button; the others have the texts too.
            view.findViewById<TextView?>(R.id.quizTitle)?.text = pick(titles, index)
            view.findViewById<TextView?>(R.id.quizDesc)?.text = pick(descs, index)
            val rating = RATES[index % RATES.size]
            val users = USERS[index % USERS.size]
            view.findViewById<TextView?>(R.id.quizRate)?.text = String.format(java.util.Locale.getDefault(), "%.1f", rating)
            view.findViewById<android.widget.RatingBar?>(R.id.quizRatingBar)?.rating = rating.toFloat()
            view.findViewById<TextView?>(R.id.quizUsers)?.text = activity.getString(R.string.ad_quiz_users, users)
            view.findViewById<View?>(R.id.quizCloseButton)?.setOnClickListener { close() }
            val button = view.findViewById<TextView>(R.id.quizButton)
            button.text = quiz.buttonText.ifEmpty { activity.getString(R.string.ad_quiz_install) }
            loadImage(view.findViewById(R.id.quizIcon), pick(quiz.appIcons, index))
            loadImage(view.findViewById(R.id.quizMedia), pick(media, index))
            // The label tint only exists on the layouts that have the AD label (the app open tab has none).
            val label = view.findViewById<View?>(R.id.quizFullLabel)
            applyColors(activity, label ?: View(activity), button)
            view.findViewById<View>(R.id.quizClose).setOnClickListener { close() }
            // The status bar, a camera cut-out and the navigation bar never cover the top bar or the buttons.
            val root = view.findViewById<View>(R.id.quizFullRoot)
            val padTop = root.paddingTop
            val padBottom = root.paddingBottom
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                v.setPadding(v.paddingLeft, padTop + bars.top, v.paddingRight, padBottom + bars.bottom)
                insets
            }
            // A tap opens the link in the in-app web view above this ad; the ad closes after the web view does (once, however many taps).
            var opened = false
            val openLink = {
                if (!opened) {
                    opened = true
                    QuizBrowser.show(activity) { close() }
                }
            }
            button.setOnClickListener { openLink() }
            view.findViewById<View>(R.id.quizFullRoot).setOnClickListener { openLink() }
            dialog.setOnDismissListener { finish(true) }
            dialog.show()
        } catch (e: Exception) {
            Log.d(TAG, "quiz dialog failed: $e")
            finish(false)
        }
    }

    /**
     * The full-screen Quiz native ad: exactly the page of the Google full native ad (`ad_native_full`, the same layout and sizes),
     * with the Quiz picture in its `ad_quiz_media` slot instead of the Google media view. A tap anywhere or on the button opens the
     * Quiz link in the in-app web view (the ad closes after it); the close button closes the ad. [finish] runs once (true when shown).
     */
    private fun showNativeFull(activity: Activity, index: Int, finish: (Boolean) -> Unit) {
        val quiz = AdRemoteConfig.config.quiz
        try {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)
            dialog.window?.setBackgroundDrawable(ColorDrawable(androidx.core.content.ContextCompat.getColor(activity, R.color.native_full_bg)))
            val page = LayoutInflater.from(activity).inflate(R.layout.ad_native_full, null)
            dialog.setContentView(page)
            FullNativeAd.keepClearOfBars(page)

            // The Quiz picture replaces the Google media view (fit inside the same area, never cropped).
            page.findViewById<View>(R.id.ad_media).visibility = View.GONE
            val media = page.findViewById<ImageView>(R.id.ad_quiz_media)
            media.visibility = View.VISIBLE
            loadImage(media, pick(quiz.nativeMedia.ifEmpty { quiz.appIcons }, index))

            page.findViewById<TextView>(R.id.ad_headline).text = pick(quiz.nativeTitle, index)
            val body = page.findViewById<TextView>(R.id.ad_body)
            val desc = pick(quiz.nativeDesc, index)
            if (desc.isEmpty()) body.visibility = View.GONE else body.text = desc
            loadImage(page.findViewById(R.id.ad_app_icon), pick(quiz.appIcons, index))

            // Chips like the Google ad's: the rating and the number of users (the price chip stays hidden).
            val stars = page.findViewById<TextView>(R.id.ad_stars)
            stars.text = String.format(java.util.Locale.getDefault(), "★ %.1f", RATES[index % RATES.size])
            stars.visibility = View.VISIBLE
            val users = page.findViewById<TextView>(R.id.ad_store)
            users.text = activity.getString(R.string.ad_quiz_users, USERS[index % USERS.size])
            users.visibility = View.VISIBLE

            val cta = page.findViewById<TextView>(R.id.ad_call_to_action)
            cta.text = quiz.buttonText.ifEmpty { activity.getString(R.string.ad_quiz_install) }
            applyColors(activity, page.findViewById(R.id.ad_attribution), cta)

            fun close() {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
            var opened = false
            val openLink = {
                if (!opened) {
                    opened = true
                    QuizBrowser.show(activity) { close() }
                }
            }
            cta.setOnClickListener { openLink() }
            page.findViewById<View>(R.id.ad_view).setOnClickListener { openLink() }
            page.findViewById<View>(R.id.ad_close).setOnClickListener { close() }
            dialog.setOnDismissListener { finish(true) }
            dialog.show()
        } catch (e: Exception) {
            Log.d(TAG, "quiz native dialog failed: $e")
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
