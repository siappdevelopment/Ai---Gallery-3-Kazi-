package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityIntro2Binding
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.viewmodel.Intro2ViewModel

class Intro2Activity : AppCompatActivity() {

    private var binding: ActivityIntro2Binding? = null
    private val viewModel: Intro2ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO2)

        val b = ActivityIntro2Binding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

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
                OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO3)
                startActivity(Intent(this, Intro3Activity::class.java))
                finish()
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
