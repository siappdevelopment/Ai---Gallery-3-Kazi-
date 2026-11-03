package com.photovideo.gallerykz.memories.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import com.photovideo.gallerykz.memories.databinding.ActivityPrivacyPolicyBinding
import com.photovideo.gallerykz.memories.helper.OnboardingPrefs

/**
 * Onboarding step after Language: what the app does with the user's data, a checkbox and Continue. Agreeing is saved and the
 * onboarding goes on with the media permission. Back leaves the screen; the next launch resumes here (the step is stored) and a
 * policy that was agreed to is skipped. The Privacy Policy link opens the Remote Config page (`Privacy_Policy`).
 */
class PrivacyPolicyActivity : AppCompatActivity() {

    private var binding: ActivityPrivacyPolicyBinding? = null

    // Continue counts once, however fast it is tapped.
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (OnboardingPrefs.isPolicyAccepted(this)) {
            // Already agreed to (a resumed onboarding): straight on.
            goNext()
            return
        }
        // Remember the step immediately so a killed app resumes here.
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_POLICY)

        val b = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupFooterText(b)
        // The row toggles the checkbox; the button follows it (the state survives recreation by itself).
        b.agreeRow.setOnClickListener { b.agreeCheckBox.toggle() }
        b.agreeCheckBox.setOnCheckedChangeListener { _, checked -> renderButton(b, checked) }
        renderButton(b, b.agreeCheckBox.isChecked)
        b.btnContinue.setOnClickListener { onContinue(b) }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private fun renderButton(b: ActivityPrivacyPolicyBinding, agreed: Boolean) {
        b.btnContinue.isEnabled = agreed
        b.btnContinue.alpha = if (agreed) 1f else 0.5f
    }

    private fun onContinue(b: ActivityPrivacyPolicyBinding) {
        if (navigated || !b.agreeCheckBox.isChecked) return
        navigated = true
        OnboardingPrefs.setPolicyAccepted(this)
        goNext()
    }

    private fun goNext() {
        navigated = true
        OnboardingPrefs.setStep(this, OnboardingPrefs.STEP_MEDIA)
        startActivity(Intent(this, MediaPermissionActivity::class.java))
        finish()
    }

    /** "Read the full Privacy Policy for details." The label opens the configured page; without a page it stays plain text. */
    private fun setupFooterText(b: ActivityPrivacyPolicyBinding) {
        val label = getString(R.string.policy_privacy_label)
        val text = getString(R.string.policy_footer, label)
        val spannable = SpannableString(text)
        val start = text.indexOf(label)
        if (label.isNotEmpty() && start >= 0) {
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = openPrivacyPolicy()

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(this@PrivacyPolicyActivity, R.color.brand_pink)
                    ds.isUnderlineText = true
                }
            }, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.policyFooter.text = spannable
        b.policyFooter.movementMethod = LinkMovementMethod.getInstance()
        b.policyFooter.highlightColor = Color.TRANSPARENT
    }

    private fun openPrivacyPolicy() {
        val url = AdRemoteConfig.config.privacyPolicyUrl
        if (url.isEmpty()) return // no page configured (yet): the label stays plain text
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.policy_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
