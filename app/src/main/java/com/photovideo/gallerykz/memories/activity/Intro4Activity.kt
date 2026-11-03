package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.helper.IntroFlow
import com.photovideo.gallerykz.memories.ads.OnboardingAds

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.databinding.ActivityIntro4Binding
import com.photovideo.gallerykz.memories.helper.OnboardingPrefs
import com.photovideo.gallerykz.memories.launcher.LauncherHomeActivity
import com.photovideo.gallerykz.memories.viewmodel.Intro4ViewModel

class Intro4Activity : AppCompatActivity() {

    private var binding: ActivityIntro4Binding? = null
    private val viewModel: Intro4ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A saved step beyond the configured screen count ends the intro at once.
        if (IntroFlow.skipIfBeyond(this, 4)) return
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO4)

        val b = ActivityIntro4Binding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.attachOnboarding(this, AdScreen.INTRO)
        // The dots and the last button follow the configured intro count.
        IntroFlow.bind(this, b.btnNext, b.introDots, 4)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnNext.setOnClickListener { viewModel.onGetStartedClicked() }

        viewModel.navigateNext.observe(this) { event ->
            // Last onboarding page: the onboarding is complete, on to the launcher.
            event.getContentIfNotHandled()?.let {
                IntroFlow.complete(this)
            }
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
