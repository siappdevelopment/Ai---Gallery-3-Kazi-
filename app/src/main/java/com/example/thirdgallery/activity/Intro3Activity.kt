package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.databinding.ActivityIntro3Binding
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.viewmodel.Intro3ViewModel

class Intro3Activity : AppCompatActivity() {

    private var binding: ActivityIntro3Binding? = null
    private val viewModel: Intro3ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO3)

        val b = ActivityIntro3Binding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnNext.setOnClickListener { viewModel.onNextClicked() }

        viewModel.navigateNext.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO4)
                startActivity(Intent(this, Intro4Activity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
