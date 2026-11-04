package com.photovideo.gallerykz.memories.events

import com.photovideo.gallerykz.memories.ads.SlotSpec

import com.photovideo.gallerykz.memories.ads.EventAdCfg

import com.photovideo.gallerykz.memories.ads.BackAds

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.photovideo.gallerykz.memories.databinding.ActivityEventPromptBinding
import com.photovideo.gallerykz.memories.launcher.LauncherHomeActivity
import com.photovideo.gallerykz.memories.launcher.LauncherSettingsActivity

/**
 * The base of the charging and the install / uninstall screens: a coloured header with what happened, a shortcut to the
 * gallery, and a hold bar that becomes the Done button. Back and Done only work once the hold ended. The screen is only for
 * the moment of the event: HOME, Recents, the screen turning off, or a launch that was held back for more than 6 seconds
 * all close it (without any ad, there is none).
 */
abstract class EventPromptActivity : AppCompatActivity() {

    private var binding: ActivityEventPromptBinding? = null
    private var holdAnimator: ValueAnimator? = null
    private var canExit = false
    private var exiting = false

    /** True while this screen itself opens another screen (Settings): that is not the user leaving. */
    private var internalNavigation = false
    private var resumed = false

    /** The next time the screen becomes visible, check that it is not a held-back (stale) launch. */
    private var checkFreshOnResume = false

    protected abstract fun kindFrom(intent: Intent?): EventKind

    /** The ad block of this screen (bottom ad, back ad, hold time) and the key its counters use. */
    protected abstract fun adConfig(): EventAdCfg
    protected abstract val adKey: String
    protected abstract fun bind(kind: EventKind)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkFreshOnResume = true
        EventNotifier.cancel(this, kindFrom(intent))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val b = ActivityEventPromptBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        bind(kindFrom(intent))
        val settings = View.OnClickListener {
            internalNavigation = true
            startActivity(LauncherSettingsActivity.intent(this))
        }
        b.ivEventSettings.setOnClickListener(settings)
        b.llOpenGallery.setOnClickListener {
            startActivity(LauncherHomeActivity.intent(this))
            finishAndRemoveTask()
        }
        b.btnEventDone.setOnClickListener { exitScreen() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back is swallowed until the hold ended.
            override fun handleOnBackPressed() = exitScreen()
        })
        startHold()
        setupBottomAd(b)
    }

    private fun setupBottomAd(b: ActivityEventPromptBinding) {
        // The IP location (state / country) for the back ad lists is looked up when the screen opens (only when App_Proxy_Check_Ip is on).
        com.photovideo.gallerykz.memories.ads.IpLocation.refresh(this)
        try {
            val cfg = adConfig()
            val spec = SlotSpec(
                show = cfg.bottomShow, native = cfg.bottomNative, bannerId = cfg.bannerId, nativeId = cfg.nativeId, large = true,
                preload = cfg.preload, cacheKey = "slot_event_$adKey",
            )
            // No Quiz ad on the charging / install screens: not at the bottom, not in the back ad, not as a fallback.
            AdHooks.attachInto(this, b.adBottomSlot, this, spec, keepContentOnFail = false, navWithBigNative = true, noQuiz = true)
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "event ad failed to attach: $e")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkFreshOnResume = !resumed
        EventNotifier.cancel(this, kindFrom(intent))
        bind(kindFrom(intent))
        // A second event while the screen is open: the content changes and the hold starts again.
        holdAnimator?.cancel()
        exiting = false
        startHold()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (checkFreshOnResume) {
            checkFreshOnResume = false
            if (isStaleLaunch(intent)) {
                leave()
                return
            }
        }
        internalNavigation = false
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /** HOME or Recents: the screen is excluded from Recents, so a hidden copy is useless. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (exiting || internalNavigation) return
        leave()
    }

    override fun onStop() {
        super.onStop()
        // Also covers the screen turning off, where onUserLeaveHint is not called.
        if (!isChangingConfigurations && !isFinishing && !exiting && !internalNavigation) leave()
    }

    private fun leave() {
        if (isFinishing || isChangingConfigurations) return
        finishAndRemoveTask()
    }

    private fun isStaleLaunch(intent: Intent?): Boolean {
        val launchedAt = intent?.getLongExtra(EXTRA_LAUNCHED_AT, 0L) ?: 0L
        return launchedAt > 0L && System.currentTimeMillis() - launchedAt > STALE_LAUNCH_MS
    }

    /** The seconds before Done works come from the config (`*_button_show_sec`); 3 s when it says nothing. */
    private fun holdMs(): Long = adConfig().buttonSec.takeIf { it > 0 }?.let { it * 1000L } ?: HOLD_MS

    // ---- hold bar -> Done ----

    private fun startHold() {
        val b = binding ?: return
        canExit = false
        b.pbEventHold.visibility = View.VISIBLE
        b.btnEventDone.visibility = View.GONE
        b.pbEventHold.progress = 0
        holdAnimator = ValueAnimator.ofInt(0, b.pbEventHold.max).apply {
            duration = holdMs()
            interpolator = LinearInterpolator()
            addUpdateListener { binding?.pbEventHold?.progress = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) showDone()
                }
            })
            start()
        }
    }

    private fun showDone() {
        val b = binding ?: return
        if (isFinishing) return
        b.pbEventHold.visibility = View.GONE
        b.btnEventDone.visibility = View.VISIBLE
        canExit = true
    }

    private fun exitScreen() {
        if (!canExit || exiting) return
        exiting = true
        // The back / Done ad (when due) comes first; the screen closes after it, whatever happens.
        BackAds.show(this, adKey, adConfig().back, "", noQuiz = true) { if (!isFinishing) finish() }
    }

    // ---- content ----

    protected fun showContent(
        header: Int,
        accent: Int,
        badgeBg: Int,
        badgeText: Int,
        title: CharSequence,
        success: CharSequence,
        @StringRes sub: Int,
        @StringRes body: Int,
        badge: CharSequence?,
        @DrawableRes hero: Int,
        @DrawableRes status: Int,
    ) {
        val b = binding ?: return
        b.eventRoot.setBackgroundColor(header)
        b.eventHeader.setBackgroundColor(header)
        window.statusBarColor = header
        b.tvEventTitle.text = title
        b.tvEventSuccess.text = success
        b.tvEventSuccess.setTextColor(accent)
        b.tvEventSubtitle.setText(sub)
        b.tvEventBody.setText(body)
        b.ivEventHero.setImageResource(hero)
        b.ivEventHero.setColorFilter(accent)
        b.ivEventHero.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(170, 255, 255, 255))
        }
        b.ivEventStatusIcon.setImageResource(status)
        b.ivEventStatusIcon.setColorFilter(accent)
        b.btnEventDone.backgroundTintList = ColorStateList.valueOf(accent)
        b.pbEventHold.progressTintList = ColorStateList.valueOf(accent)
        b.tvEventBadge.visibility = if (badge.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.tvEventBadge.text = badge
        b.tvEventBadge.setTextColor(badgeText)
        b.tvEventBadge.background?.mutate()?.setTint(badgeBg)
    }

    /** The battery level in percent; 0 when it cannot be read. */
    protected fun batteryPercent(): Int {
        val manager = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return 0
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else 0
    }

    override fun onDestroy() {
        holdAnimator?.cancel()
        holdAnimator = null
        binding = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "event_kind"
        const val EXTRA_LAUNCHED_AT = "event_launched_at"

        /** The hold before Done works: the skill holds the screen for ads; here it is a short moment. */
        private const val HOLD_MS = 3_000L
        private const val STALE_LAUNCH_MS = 6_000L
    }
}
