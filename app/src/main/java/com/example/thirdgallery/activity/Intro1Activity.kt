package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.databinding.ActivityIntro1Binding
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.viewmodel.Intro1ViewModel

class Intro1Activity : AppCompatActivity() {

    private var binding: ActivityIntro1Binding? = null
    private val viewModel: Intro1ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO1)

        val b = ActivityIntro1Binding.inflate(layoutInflater)
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
                OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_INTRO2)
                startActivity(Intent(this, Intro2Activity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
