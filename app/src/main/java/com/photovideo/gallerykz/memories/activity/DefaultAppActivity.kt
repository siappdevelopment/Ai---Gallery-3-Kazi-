package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.helper.OnboardingFlow
import com.photovideo.gallerykz.memories.ads.OnboardingAds

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.photovideo.gallerykz.memories.notification.DefaultLauncherReminder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.databinding.ActivityDefaultAppBinding
import com.photovideo.gallerykz.memories.helper.OnboardingEnter
import com.photovideo.gallerykz.memories.helper.OnboardingPrefs
import com.photovideo.gallerykz.memories.helper.OnboardingRouter
import com.photovideo.gallerykz.memories.launcher.DefaultHomeRequest
import com.photovideo.gallerykz.memories.launcher.LauncherNav
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import android.content.Intent
import com.photovideo.gallerykz.memories.launcher.LauncherHomeActivity

/**
 * Onboarding "Default app" (design 07), the first step of the onboarding. The first time, Android's Home app screen opens
 * before it; "Set as Default" then shows the same system choice as the launcher's own card (the Home role dialog, or the
 * Home settings). The language screen follows only when this app really became the Home app (or with Skip); coming back from the
 * system screen without choosing it keeps the user here.
 */
class DefaultAppActivity : com.photovideo.gallerykz.memories.helper.BaseLocaleActivity() {

    private var binding: ActivityDefaultAppBinding? = null
    private var completed = false

    // The bottom ad slot, started once (the first time this screen is really in front).
    private var adSlot: com.photovideo.gallerykz.memories.ads.AdSlot? = null
    private var adStarted = false

    // The system Home screen opened at the start: the ad waits until it was shown and the user is back.
    private var awaitingSystem = false
    private var leftForSystem = false

    // The system Home screen is to be opened at the first resume (see openSystemWhenVisible).
    private var systemFirstPending = false

    // True once the user tapped Set as Default and the system choice is open (kept over recreation).
    private var userAsked = false

    // Force_Set_As_Default on a fresh launch after the onboarding was done: the step is left alone and Home follows (see SplashActivity).
    private var forcedReask = false

    // The Skip button waits SKIP_DELAY_MS after the screen opened; the callback is removed with the screen.
    private var skipDelayPassed = false
    private val skipHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val showSkip = Runnable {
        skipDelayPassed = true
        updateSkip()
    }

    // Opened by the "Make Gallery your home screen" notification (see DefaultLauncherReminder).
    private var reminderEntry = false

    // A property, so the launcher is registered again after the process was recreated.
    private val defaultHome = DefaultHomeRequest(this) { onRequestOver() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userAsked = savedInstanceState?.getBoolean(KEY_USER_ASKED) ?: false
        // The "Make Gallery your home screen" notification: like a forced re-ask (the onboarding step is left alone and the launcher follows),
        // but the system Home settings open first and Back or Cancel both end at the launcher.
        reminderEntry = intent.getBooleanExtra(DefaultLauncherReminder.EXTRA_FROM_REMINDER, false)
        forcedReask = intent.getBooleanExtra(EXTRA_FORCED_REASK, false) || reminderEntry
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash (not when the finished onboarding asks again).
        if (!forcedReask) OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_DEFAULT)
        if (finishIfDefault()) return

        val b = ActivityDefaultAppBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        // The ad is requested only once this screen is really in front (see onResume), never while the system screen is on top.
        adSlot = AdHooks.attachOnboarding(this, AdScreen.DEFAULT_APP, autoStart = false)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.main.setPadding(bars.left, bars.top, bars.right, 0)
            b.sheet.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        b.btnSetDefault.setOnClickListener {
            userAsked = true
            // The same system Home app settings screen that opens first thing in the onboarding (the guide card shows over it), never the
            // role chooser dialog. Coming back, onRequestOver / onResume read the real state again (finishIfDefault).
            if (!defaultHome.isInFlight && !defaultHome.requestSettings()) {
                // This phone has no such settings screen: the usual request (it reports a failure itself).
                defaultHome.request(settingsFirst = true)
            }
        }
        // Skip: this step is done without choosing, the same way as answering the system choice with Cancel. With Force_Set_As_Default only
        // a confirmed Home app moves on, so there is no Skip then. Without it the button appears only after SKIP_DELAY_MS.
        skipDelayPassed = savedInstanceState?.getBoolean(KEY_SKIP_SHOWN) ?: false
        updateSkip()
        if (!skipDelayPassed) skipHandler.postDelayed(showSkip, SKIP_DELAY_MS)
        b.btnSkip.setOnClickListener { goToLanguage() }
        if (savedInstanceState == null) OnboardingEnter.play(b.pill, b.sheet)
        // Every time this screen is opened afresh (a launch, not a recreation of the same screen), Android's own Home app screen opens before
        // this explanation (see the reference video), whatever Force_Set_As_Default says; if it is dismissed without a choice, the user lands
        // here and can try again with Set as Default. Not again for a recreation of the same screen (rotation, language, process restore).
        // The splash already opened the Home settings (and the guide card) before this screen: they are not opened a second time.
        val systemShown = intent.getBooleanExtra(EXTRA_SYSTEM_SHOWN, false)
        if (reminderEntry) {
            // Back from the system screen without the choice: this screen stays and Set as Default asks again. Back here ends at the launcher.
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goToLanguage()
            })
            if (savedInstanceState == null && !systemShown) openSystemWhenVisible()
        } else if (!forcedReask && savedInstanceState == null) {
            OnboardingPrefs.setDefaultSystemShown(this)
            if (!systemShown) openSystemWhenVisible()
        }
    }

    /**
     * The system Home settings open once this screen is really on screen (first resume, after its first frame), not from onCreate. The guide
     * card is started a moment after the settings; with no overlay permission it is a normal activity start from the background, which the
     * system only allows shortly after this app had a visible window. Started from onCreate (a first launch) there was none yet, so the
     * guide start was refused and the tutorial was missing; from a button tap the screen had been visible, so it worked.
     */
    private fun openSystemWhenVisible() {
        systemFirstPending = true
        awaitingSystem = true
    }

    override fun onPause() {
        super.onPause()
        // The system Home settings screen is now in front.
        if (awaitingSystem) leftForSystem = true
    }

    override fun onResume() {
        super.onResume()
        if (systemFirstPending) {
            systemFirstPending = false
            window.decorView.post {
                if (isFinishing || isDestroyed) return@post
                if (!defaultHome.requestSettings()) {
                    // No such screen on this phone: nothing to wait for.
                    awaitingSystem = false
                    startAd()
                }
            }
            return
        }
        // The bottom ad starts when this screen is in front: not before the system screen has been shown and closed.
        if (awaitingSystem && leftForSystem) awaitingSystem = false
        if (!awaitingSystem) startAd()
        // The user may have chosen this app in the system settings and come back.
        finishIfDefault()
        updateSkip()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_USER_ASKED, userAsked)
        outState.putBoolean(KEY_SKIP_SHOWN, skipDelayPassed)
    }

    private fun startAd() {
        if (adStarted || isFinishing || isDestroyed) return
        adStarted = true
        adSlot?.start()
    }

    /**
     * The Home settings the user opened with Set as Default are closed again. Only a confirmed Home app moves the onboarding on ([finishIfDefault]);
     * coming back without having chosen this app (Back, or another app picked) keeps the user on this screen, with no ad and no navigation, to
     * try again or to use Skip. The one exception is the screen opened by the reminder notification, whose Back ends at the launcher.
     */
    private fun onRequestOver() {
        if (finishIfDefault()) return
        if (userAsked && reminderEntry) goToLanguage()
    }

    /** True (and the step is left) when this app already is the Home app. */
    private fun finishIfDefault(): Boolean {
        if (completed) return true
        if (!LauncherNav.isHomeAppOrRoleHolder(this)) return false
        goToLanguage()
        return true
    }

    /** This step is done for good. The language screen is next; the launcher only opens when the whole onboarding is done. */
    private fun goToLanguage() {
        if (completed) return
        completed = true
        // Without the splash, and without a second copy if the system already brought the next screen up.
        OnboardingAds.afterDefault(this) {
            if (forcedReask) {
                startActivity(Intent(this, LauncherHomeActivity::class.java))
            } else {
                OnboardingFlow.advance(this, OnboardingFlow.Screen.DEFAULT_APP, bringForward = true)
            }
            finish()
        }
    }

    companion object {
        /** Set by the splash when Force_Set_As_Default is on, the onboarding is done and this app is not the Home app. */
        const val EXTRA_FORCED_REASK = "forced_reask"

        /** Set by the splash when it opened the system Home settings itself, so this screen does not open them again. */
        const val EXTRA_SYSTEM_SHOWN = "system_home_shown"
        private const val KEY_USER_ASKED = "user_asked"
        private const val KEY_SKIP_SHOWN = "skip_shown"

        /** The Skip button appears this long after the screen opened (Force_Set_As_Default off). */
        private const val SKIP_DELAY_MS = 3000L
    }

    /** The Skip button: hidden until the delay has passed, and never shown while Force_Set_As_Default is on (read when it is applied). */
    private fun updateSkip() {
        val button = binding?.btnSkip ?: return
        if (isFinishing || isDestroyed) return
        val show = skipDelayPassed && !AdRemoteConfig.config.forceSetAsDefault
        if (!show) {
            button.animate().cancel()
            button.alpha = 1f
            button.visibility = android.view.View.GONE
            return
        }
        if (button.visibility == android.view.View.VISIBLE) return
        // The same appearance as the Continue button of the language screen (LanguageActivity.revealButton): fades in over 200 ms.
        button.alpha = 0f
        button.visibility = android.view.View.VISIBLE
        button.animate().alpha(1f).setDuration(200L).start()
    }

    override fun onDestroy() {
        // No late visibility change after the screen is gone.
        skipHandler.removeCallbacks(showSkip)
        binding?.btnSkip?.animate()?.cancel()
        binding = null
        super.onDestroy()
    }
}
