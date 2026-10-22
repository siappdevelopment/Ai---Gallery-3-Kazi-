package com.example.thirdgallery.activity

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.databinding.ActivityNotiPermissionBinding
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.helper.OnboardingPrefs
import com.example.thirdgallery.viewmodel.NotiPermissionViewModel

class NotiPermissionActivity : AppCompatActivity() {

    private var binding: ActivityNotiPermissionBinding? = null
    private val viewModel: NotiPermissionViewModel by viewModels()
    private var requesting = false

    // Allowed or denied, the onboarding is finished once the user has answered.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requesting = false
        viewModel.onFinished()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Remember the step immediately so a killed app resumes here after Splash.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_NOTI)

        val b = ActivityNotiPermissionBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnTurnOn.setOnClickListener { requestNotifications() }
        b.btnNotNow.setOnClickListener { viewModel.onFinished() }

        viewModel.navigateNext.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_DONE)
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }
        }
    }

    private fun requestNotifications() {
        if (requesting) return
        // Before API 33 (or already allowed) there is no runtime dialog to show.
        if (!MediaPermissionHelper.needsNotificationPermission(this)) {
            viewModel.onFinished()
            return
        }
        requesting = true
        try {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            requesting = false
            viewModel.onFinished()
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}
