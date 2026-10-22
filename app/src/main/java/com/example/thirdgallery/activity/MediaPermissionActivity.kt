package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.databinding.ActivityMediaPermissionBinding
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.viewmodel.MediaPermissionViewModel
import com.example.thirdgallery.viewmodel.MediaPermissionViewModel.Destination

class MediaPermissionActivity : AppCompatActivity() {

    private var binding: ActivityMediaPermissionBinding? = null
    private val viewModel: MediaPermissionViewModel by viewModels()
    private var requesting = false

    // Registered as a property so the result is still delivered after process recreation.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requesting = false
        viewModel.onAccessResult(MediaPermissionHelper.hasAccess(this), canOpenDenied = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_MEDIA)

        val b = ActivityMediaPermissionBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnContinue.setOnClickListener { requestAccess() }

        viewModel.navigate.observe(this) { event ->
            event.getContentIfNotHandled()?.let { destination ->
                when (destination) {
                    Destination.NOTIFICATION -> {
                        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_NOTI)
                        startActivity(Intent(this, NotiPermissionActivity::class.java))
                    }
                    Destination.DENIED -> {
                        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_MEDIA_DENIED)
                        startActivity(Intent(this, MediaDeniteActivity::class.java))
                    }
                }
                finish()
            }
        }

        // Already allowed (e.g. granted earlier): nothing to ask.
        viewModel.onAccessChecked(MediaPermissionHelper.hasAccess(this))
    }

    private fun requestAccess() {
        if (requesting) return
        if (MediaPermissionHelper.hasAccess(this)) {
            viewModel.onAccessResult(granted = true, canOpenDenied = true)
            return
        }
        requesting = true
        try {
            permissionLauncher.launch(MediaPermissionHelper.requiredPermissions())
        } catch (e: Exception) {
            requesting = false
            viewModel.onAccessResult(MediaPermissionHelper.hasAccess(this), canOpenDenied = true)
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
