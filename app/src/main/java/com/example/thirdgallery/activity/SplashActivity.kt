package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.example.thirdgallery.callend.CallEndPermission
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivitySplashBinding
import com.example.thirdgallery.helper.OnboardingRouter
import com.example.thirdgallery.launcher.LauncherHomeActivity

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

    private val phoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        phoneAnswered = true
        navigateWhenReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ThirdGallery)
        super.onCreate(savedInstanceState)
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
        askPhoneAccess()
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
        if (delayDone && phoneAnswered) navigateNext()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(openNext)
        handler.postDelayed(openNext, SPLASH_DELAY_MS)
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

    private fun navigateNext() {
        if (isFinishing || isDestroyed) return
        // Resume the onboarding flow where the user left off.
        startActivity(OnboardingRouter.intent(this))
        finish()
    }

    private companion object {
        const val SPLASH_DELAY_MS = 2000L
    }
}
