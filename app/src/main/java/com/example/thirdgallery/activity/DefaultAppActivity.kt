package com.example.thirdgallery.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.databinding.ActivityDefaultAppBinding
import com.example.thirdgallery.helper.OnboardingEnter
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.helper.OnboardingRouter
import com.example.thirdgallery.launcher.DefaultHomeRequest
import com.example.thirdgallery.launcher.LauncherNav

/**
 * Onboarding "Default app" (design 07), the first step of the onboarding. The first time, Android's Home app screen opens
 * before it; "Set as Default" then shows the same system choice as the launcher's own card (the Home role dialog, or the
 * Home settings). Once the user has answered it (picked this app or cancelled) the language screen follows and this screen is
 * not shown again; only dismissing the system screen that opens by itself keeps the user here.
 */
class DefaultAppActivity : AppCompatActivity() {

    private var binding: ActivityDefaultAppBinding? = null
    private var completed = false

    // True once the user tapped Set as Default and the system choice is open (kept over recreation).
    private var userAsked = false

    // A property, so the launcher is registered again after the process was recreated.
    private val defaultHome = DefaultHomeRequest(this) { onRequestOver() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userAsked = savedInstanceState?.getBoolean(KEY_USER_ASKED) ?: false
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_DEFAULT)
        if (finishIfDefault()) return

        val b = ActivityDefaultAppBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.main.setPadding(bars.left, bars.top, bars.right, 0)
            b.sheet.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        b.btnSetDefault.setOnClickListener {
            userAsked = true
            defaultHome.request()
        }
        if (savedInstanceState == null) OnboardingEnter.play(b.pill, b.sheet)
        // The first time, Android's own Home app screen opens before this explanation (see the reference video); if it is
        // dismissed without a choice, the user lands here and can try again with Set as Default. Never twice.
        if (!OnboardingPrefs.isDefaultSystemShown(this)) {
            OnboardingPrefs.setDefaultSystemShown(this)
            defaultHome.requestSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have chosen this app in the system settings and come back.
        finishIfDefault()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_USER_ASKED, userAsked)
    }

    /**
     * The system choice the user opened with Set as Default is over: whether they picked this app or cancelled, the
     * onboarding goes on with the language. (The choice the system screen shows by itself at the start is different:
     * dismissing that one keeps the user here.)
     */
    private fun onRequestOver() {
        if (finishIfDefault()) return
        if (userAsked) goToLanguage()
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
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_LANGUAGE)
        // Without the splash, and without a second copy if the system already brought the language screen up.
        startActivity(OnboardingRouter.resume(this))
        finish()
    }

    private companion object {
        const val KEY_USER_ASKED = "user_asked"
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
