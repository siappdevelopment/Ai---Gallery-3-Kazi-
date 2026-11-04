package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.OnboardingAds

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdRemoteConfig

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.photovideo.gallerykz.memories.callend.CallEndPermission
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivitySplashBinding
import com.photovideo.gallerykz.memories.helper.OnboardingRouter
import com.photovideo.gallerykz.memories.launcher.LauncherHomeActivity
import com.photovideo.gallerykz.memories.launcher.LauncherNav

class SplashActivity : AppCompatActivity() {

    private var binding: ActivitySplashBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private val openNext = Runnable {
        delayDone = true
        navigateWhenReady()
    }

    // The splash waits for both its delay and the answer to the one-time phone permission question.
    private var delayDone = false
    private var phoneAnswered = false

    // On the very first start the Remote Config (Show_Screen_Flow) may not be here yet: wait for it, but never longer than CONFIG_WAIT_MS.
    private var configReady = false
    private val configTimeout = Runnable {
        configReady = true
        navigateWhenReady()
    }

    private val phoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        phoneAnswered = true
        navigateWhenReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ThirdGallery)
        super.onCreate(savedInstanceState)
        if (restartInFreshTask()) return
        enableEdgeToEdge()

        val b = ActivitySplashBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        playEntranceAnimation(b, animate = savedInstanceState == null)
        AdHooks.attachBottom(this, AdScreen.SPLASH)
        if (AdRemoteConfig.loaded || AdRemoteConfig.settled) {
            configReady = true
        } else {
            handler.postDelayed(configTimeout, CONFIG_WAIT_MS)
            AdRemoteConfig.whenSettled {
                configReady = true
                if (!isFinishing && !isDestroyed) navigateWhenReady()
            }
        }
        askPhoneAccess()
    }

    /**
     * The splash is singleTask, so a tap on the icon always shows it, also when the app already has a task. When that task is an old one
     * (not the splash at its root, for instance the launcher home left over from the time this app was the Home app) it can be a Home type
     * task, which the system does not list in Recents. Then (this app is no longer the Home app) the old task is removed and the splash is
     * started again in a new, normal task. Returns true when this instance is going away.
     */
    private fun restartInFreshTask(): Boolean {
        if (isTaskRoot || intent.getBooleanExtra(EXTRA_FRESH, false) || LauncherNav.isHomeAppOrRoleHolder(this)) return false
        val again = Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_FRESH, true)
        finishAndRemoveTask()
        startActivity(again)
        return true
    }

    /**
     * The call summary needs phone access: asked here first, once per install. A denial (or no answer) never blocks the
     * onboarding and is not asked again here; the gallery page asks again when the user swipes to it.
     */
    private fun askPhoneAccess() {
        if (CallEndPermission.isGranted(this) || CallEndPermission.wasAskedAtSplash(this)) {
            phoneAnswered = true
            return
        }
        CallEndPermission.markAskedAtSplash(this)
        try {
            phoneLauncher.launch(CallEndPermission.PERMISSION)
        } catch (e: Exception) {
            phoneAnswered = true
        }
    }

    private fun navigateWhenReady() {
        if (delayDone && phoneAnswered && configReady) navigateNext()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(openNext)
        handler.postDelayed(openNext, splashDelayMs())
    }

    override fun onStop() {
        handler.removeCallbacks(openNext)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding = null
        super.onDestroy()
    }

    private fun playEntranceAnimation(b: ActivitySplashBinding, animate: Boolean) {
        if (!animate) return
        b.ivLogo.apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500L).start()
        }
        b.viewGlow.apply {
            alpha = 0f
            animate().alpha(1f).setDuration(700L).start()
        }
        listOf(b.tvTitle, b.tvTagline).forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 16f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(200L + index * 100L)
                .setDuration(450L)
                .start()
        }
    }

    /** `Splash_Duration` of the config (seconds), else 2 s. */
    private fun splashDelayMs(): Long {
        val seconds = AdRemoteConfig.config.splash.durationSec
        return if (seconds > 0) seconds * 1000L else SPLASH_DELAY_MS
    }

    private var navigating = false

    private fun navigateNext() {
        if (isFinishing || isDestroyed || navigating) return
        navigating = true
        // The after-splash ad (when due) comes first; then the onboarding resumes where the user left off.
        OnboardingAds.afterSplash(this) {
            if (!isFinishing && !isDestroyed) {
                removeOldAppTasks()
                startActivity(OnboardingRouter.intent(this))
                finish()
            }
        }
    }

    /**
     * When this app is no longer the Home app, an old task of it (the launcher home from the time it was the Home app) can still be
     * alive. That task is a Home type task, which Recents does not list, and the singleTask launcher home would be brought back from it
     * instead of being created in this splash's normal task. So the other tasks of the app are removed before the next screen starts.
     */
    private fun removeOldAppTasks() {
        if (LauncherNav.isHomeAppOrRoleHolder(this)) return
        // The launcher home of an old Home type task is not in the list below: it is ended through its own activity.
        com.photovideo.gallerykz.memories.launcher.AppRecentsKey.finishOtherHomes(this)
        try {
            val manager = getSystemService(android.app.ActivityManager::class.java) ?: return
            @Suppress("DEPRECATION")
            manager.appTasks.forEach { task ->
                if (task.taskInfo?.id != taskId) task.finishAndRemoveTask()
            }
        } catch (e: Exception) {
            // The old task simply stays.
        }
    }

    private companion object {
        const val EXTRA_FRESH = "splash_fresh_task"
        const val SPLASH_DELAY_MS = 2000L
        const val CONFIG_WAIT_MS = 5000L
    }
}
