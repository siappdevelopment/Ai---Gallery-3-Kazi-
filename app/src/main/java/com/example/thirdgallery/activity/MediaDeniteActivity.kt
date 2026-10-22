package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.databinding.ActivityMediaDeniteBinding
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.viewmodel.MediaPermissionViewModel

class MediaDeniteActivity : AppCompatActivity() {

    private var binding: ActivityMediaDeniteBinding? = null
    private val viewModel: MediaPermissionViewModel by viewModels()
    private var requesting = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requesting = false
        // Denied again: stay on this screen.
        viewModel.onAccessResult(MediaPermissionHelper.hasAccess(this), canOpenDenied = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_MEDIA_DENIED)

        val b = ActivityMediaDeniteBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnOpenSettings.setOnClickListener { MediaPermissionHelper.openAppSettings(this) }
        b.btnTryAgain.setOnClickListener { requestAccess() }

        viewModel.navigate.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_NOTI)
                startActivity(Intent(this, NotiPermissionActivity::class.java))
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings (or granted some other way): continue if access is available now.
        viewModel.onAccessChecked(MediaPermissionHelper.hasAccess(this))
    }

    private fun requestAccess() {
        if (requesting) return
        if (MediaPermissionHelper.hasAccess(this)) {
            viewModel.onAccessChecked(true)
            return
        }
        requesting = true
        try {
            permissionLauncher.launch(MediaPermissionHelper.requiredPermissions())
        } catch (e: Exception) {
            requesting = false
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
