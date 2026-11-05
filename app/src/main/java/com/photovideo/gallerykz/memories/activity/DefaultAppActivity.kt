package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.helper.OnboardingFlow
import com.photovideo.gallerykz.memories.ads.OnboardingAds

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
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
 * Home settings). Once the user has answered it (picked this app or cancelled) the language screen follows and this screen is
 * not shown again; only dismissing the system screen that opens by itself keeps the user here.
 */
class DefaultAppActivity : AppCompatActivity() {

    private var binding: ActivityDefaultAppBinding? = null
    private var completed = false

    // The bottom ad slot, started once (the first time this screen is really in front).
    private var adSlot: com.photovideo.gallerykz.memories.ads.AdSlot? = null
    private var adStarted = false

    // The system Home screen opened at the start: the ad waits until it was shown and the user is back.
    private var awaitingSystem = false
    private var leftForSystem = false

    // True once the user tapped Set as Default and the system choice is open (kept over recreation).
    private var userAsked = false

    // Force_Set_As_Default on a fresh launch after the onboarding was done: the step is left alone and Home follows (see SplashActivity).
    private var forcedReask = false

    // A property, so the launcher is registered again after the process was recreated.
    private val defaultHome = DefaultHomeRequest(this) { onRequestOver() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userAsked = savedInstanceState?.getBoolean(KEY_USER_ASKED) ?: false
        forcedReask = intent.getBooleanExtra(EXTRA_FORCED_REASK, false)
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
            defaultHome.request(settingsFirst = AdRemoteConfig.config.forceSetAsDefault)
        }
        if (savedInstanceState == null) OnboardingEnter.play(b.pill, b.sheet)
        // The first time, Android's own Home app screen opens before this explanation (see the reference video); if it is
        // dismissed without a choice, the user lands here and can try again with Set as Default. Never twice.
        if (!forcedReask && !OnboardingPrefs.isDefaultSystemShown(this)) {
            OnboardingPrefs.setDefaultSystemShown(this)
            awaitingSystem = defaultHome.requestSettings()
        }
    }

    override fun onPause() {
        super.onPause()
        // The system Home settings screen is now in front.
        if (awaitingSystem) leftForSystem = true
    }

    override fun onResume() {
        super.onResume()
        // The bottom ad starts when this screen is in front: not before the system screen has been shown and closed.
        if (awaitingSystem && leftForSystem) awaitingSystem = false
        if (!awaitingSystem) startAd()
        // The user may have chosen this app in the system settings and come back.
        finishIfDefault()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_USER_ASKED, userAsked)
    }

    private fun startAd() {
        if (adStarted || isFinishing || isDestroyed) return
        adStarted = true
        adSlot?.start()
    }

    /**
     * The system choice the user opened with Set as Default is over: whether they picked this app or cancelled, the
     * onboarding goes on with the language. (The choice the system screen shows by itself at the start is different:
     * dismissing that one keeps the user here.)
     */
    private fun onRequestOver() {
        if (finishIfDefault()) return
        // Force_Set_As_Default: only a confirmed Home app moves on, otherwise the user stays here for another try.
        if (userAsked && !AdRemoteConfig.config.forceSetAsDefault) goToLanguage()
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
        private const val KEY_USER_ASKED = "user_asked"
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
