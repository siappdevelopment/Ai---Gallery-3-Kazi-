package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.databinding.ActivityIntro4Binding
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.viewmodel.Intro4ViewModel

class Intro4Activity : AppCompatActivity() {

    private var binding: ActivityIntro4Binding? = null
    private val viewModel: Intro4ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO4)

        val b = ActivityIntro4Binding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnNext.setOnClickListener { viewModel.onGetStartedClicked() }

        viewModel.navigateNext.observe(this) { event ->
            // Last onboarding page: continue to the permission flow.
            event.getContentIfNotHandled()?.let {
                OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_MEDIA)
                startActivity(Intent(this, MediaPermissionActivity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
