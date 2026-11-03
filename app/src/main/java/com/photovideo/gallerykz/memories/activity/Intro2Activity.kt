package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.helper.IntroFlow
import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityIntro2Binding
import com.photovideo.gallerykz.memories.helper.OnboardingPrefs
import com.photovideo.gallerykz.memories.viewmodel.Intro2ViewModel

class Intro2Activity : AppCompatActivity() {

    private var binding: ActivityIntro2Binding? = null
    private val viewModel: Intro2ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A saved step beyond the configured screen count ends the intro at once.
        if (IntroFlow.skipIfBeyond(this, 2)) return
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO2)

        val b = ActivityIntro2Binding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.attachOnboarding(this, AdScreen.INTRO)
        // The dots and the last button follow the configured intro count.
        IntroFlow.bind(this, b.btnNext, b.introDots, 2)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.tvHighlightCount.text = resources.getQuantityString(R.plurals.intro2_photos, HIGHLIGHT_PHOTOS, HIGHLIGHT_PHOTOS)
        b.tvPlaceCount.text = resources.getQuantityString(R.plurals.intro2_photos, PLACE_PHOTOS, PLACE_PHOTOS)

        b.btnNext.setOnClickListener { viewModel.onNextClicked() }

        viewModel.navigateNext.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                IntroFlow.advance(this, 2)
            }
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private companion object {
        const val HIGHLIGHT_PHOTOS = 38
        const val PLACE_PHOTOS = 84
    }
}
