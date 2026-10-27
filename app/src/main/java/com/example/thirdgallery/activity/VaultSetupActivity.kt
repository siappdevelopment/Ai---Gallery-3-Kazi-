package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityVaultSetupBinding
import com.example.thirdgallery.databinding.ItemVaultQuestionBinding
import com.example.thirdgallery.model.VaultPinError
import com.example.thirdgallery.model.VaultPinMode
import com.example.thirdgallery.model.VaultPinUi
import com.example.thirdgallery.model.VaultStep
import com.example.thirdgallery.viewmodel.VaultSetupViewModel

/**
 * Photo vault setup and unlock (designs 60-68): intro, create / confirm PIN, recovery question and
 * fingerprint on first use, and the PIN pad (with fingerprint) every time the vault is opened after that.
 * Once the vault is open it hands over to [VaultMediaActivity].
 */
class VaultSetupActivity : AppCompatActivity() {

    private var binding: ActivityVaultSetupBinding? = null
    private val viewModel: VaultSetupViewModel by viewModels()

    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Built here, not on first use, so the prompt survives the activity being recreated while it is open.
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.onFingerprintSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Dismissing the prompt ("Use PIN instead", back, tap outside) just leaves the screen as it was.
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        return
                    }
                    Toast.makeText(applicationContext, errString, Toast.LENGTH_SHORT).show()
                }
            },
        )

        val b = ActivityVaultSetupBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        // The recovery answer field needs the keyboard inset too, so its buttons stay visible.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) { viewModel.onBack() }
        setUpIntro(b)
        setUpPin(b)
        setUpRecovery(b)
        setUpFingerprint(b)

        viewModel.step.observe(this) { showStep(b, it) }
        viewModel.pin.observe(this) { renderPin(b, it) }
        viewModel.message.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show()
            }
        }
        viewModel.showFingerprintPrompt.observe(this) { event ->
            event.getContentIfNotHandled()?.let { showFingerprintPrompt() }
        }
        viewModel.openVault.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                startActivity(VaultMediaActivity.intent(this))
                finish()
            }
        }
        viewModel.finish.observe(this) { event ->
            event.getContentIfNotHandled()?.let { finish() }
        }
    }

    private fun setUpIntro(b: ActivityVaultSetupBinding) {
        b.panelIntro.btnBack.setOnClickListener { viewModel.onBack() }
        b.panelIntro.btnSetUp.setOnClickListener { viewModel.startSetup() }
    }

    private fun setUpPin(b: ActivityVaultSetupBinding) {
        val pin = b.panelPin
        pin.btnBack.setOnClickListener { viewModel.onBack() }
        listOf(
            pin.key0, pin.key1, pin.key2, pin.key3, pin.key4,
            pin.key5, pin.key6, pin.key7, pin.key8, pin.key9,
        ).forEachIndexed { digit, key -> key.setOnClickListener { viewModel.onDigit(digit) } }
        pin.keyDelete.setOnClickListener { viewModel.onDelete() }
        pin.keyFingerprint.setOnClickListener { viewModel.onFingerprintKey() }
        // The forgot-PIN recovery screen is not part of this flow yet.
        pin.btnForgot.setOnClickListener {
            Toast.makeText(applicationContext, R.string.albums_coming_soon, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpRecovery(b: ActivityVaultSetupBinding) {
        val recovery = b.panelRecovery
        recovery.btnBack.setOnClickListener { viewModel.onBack() }
        recovery.btnSkip.setOnClickListener { viewModel.skipRecovery() }
        recovery.btnSave.setOnClickListener { viewModel.saveRecovery() }

        questions(b).forEachIndexed { index, (question, text) ->
            question.tvQuestion.setText(text)
            question.root.setOnClickListener { viewModel.selectQuestion(index) }
        }
        viewModel.question.observe(this) { selected ->
            questions(b).forEachIndexed { index, (question, _) ->
                val on = index == selected
                question.root.isSelected = on
                question.radio.isSelected = on
                question.ivCheck.visibility = if (on) View.VISIBLE else View.GONE
            }
        }

        if (recovery.etAnswer.text.toString() != viewModel.answer) recovery.etAnswer.setText(viewModel.answer)
        recovery.etAnswer.doOnTextChanged { text, _, _, _ ->
            viewModel.answer = text?.toString().orEmpty()
            recovery.btnSave.alpha = if (viewModel.answer.trim().length >= MIN_ANSWER) 1f else 0.5f
        }
        recovery.btnSave.alpha = if (viewModel.answer.trim().length >= MIN_ANSWER) 1f else 0.5f
    }

    private fun questions(b: ActivityVaultSetupBinding): List<Pair<ItemVaultQuestionBinding, Int>> = listOf(
        b.panelRecovery.question1 to R.string.vault_recovery_q1,
        b.panelRecovery.question2 to R.string.vault_recovery_q2,
        b.panelRecovery.question3 to R.string.vault_recovery_q3,
    )

    private fun setUpFingerprint(b: ActivityVaultSetupBinding) {
        b.panelFingerprint.btnUse.setOnClickListener { viewModel.useFingerprint() }
        b.panelFingerprint.btnLater.setOnClickListener { viewModel.skipFingerprint() }
    }

    private fun showStep(b: ActivityVaultSetupBinding, step: VaultStep) {
        b.panelIntro.root.visibility = if (step == VaultStep.INTRO) View.VISIBLE else View.GONE
        b.panelPin.root.visibility = if (step == VaultStep.PIN) View.VISIBLE else View.GONE
        b.panelRecovery.root.visibility = if (step == VaultStep.RECOVERY) View.VISIBLE else View.GONE
        b.panelFingerprint.root.visibility = if (step == VaultStep.FINGERPRINT) View.VISIBLE else View.GONE
        b.panelRecovery.tvStep.text = getString(R.string.vault_step, 2, STEPS)
        b.panelFingerprint.tvStep.text = getString(R.string.vault_step, 3, STEPS)
        if (step != VaultStep.RECOVERY) {
            WindowCompat.getInsetsController(window, b.root).hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun renderPin(b: ActivityVaultSetupBinding, ui: VaultPinUi) {
        val pin = b.panelPin
        val (title, desc) = when (ui.mode) {
            VaultPinMode.CREATE -> R.string.vault_pin_create_title to R.string.vault_pin_create_desc
            VaultPinMode.CONFIRM -> R.string.vault_pin_confirm_title to R.string.vault_pin_confirm_desc
            VaultPinMode.UNLOCK -> R.string.vault_pin_unlock_title to R.string.vault_pin_unlock_desc
        }
        pin.tvTitle.setText(title)
        pin.tvDesc.setText(desc)

        // The step pill only belongs to the setup; unlocking has no steps.
        val setup = ui.mode != VaultPinMode.UNLOCK
        pin.tvStep.visibility = if (setup) View.VISIBLE else View.GONE
        pin.tvStep.text = getString(R.string.vault_step, 1, STEPS)

        listOf(pin.dot1, pin.dot2, pin.dot3, pin.dot4).forEachIndexed { index, dot ->
            val filled = index < ui.filled
            dot.isSelected = filled
            dot.isActivated = ui.error != null && ui.filled == 0
            val scale = if (filled) DOT_FILLED_SCALE else 1f
            dot.scaleX = scale
            dot.scaleY = scale
        }

        pin.tvError.text = when (val error = ui.error) {
            null -> ""
            VaultPinError.Mismatch -> getString(R.string.vault_pin_mismatch)
            is VaultPinError.Wrong ->
                if (error.attempts >= FAILED_ATTEMPTS_SHOWN) {
                    getString(R.string.vault_pin_wrong_attempts, error.attempts)
                } else {
                    getString(R.string.vault_pin_wrong)
                }
        }

        pin.keyFingerprint.visibility = if (ui.fingerprintKey) View.VISIBLE else View.INVISIBLE
        pin.keyFingerprint.isEnabled = ui.fingerprintKey
        pin.btnForgot.visibility = if (ui.mode == VaultPinMode.UNLOCK) View.VISIBLE else View.INVISIBLE
    }

    private fun showFingerprintPrompt() {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.vault_scan_title))
            .setSubtitle(getString(R.string.vault_scan_desc))
            .setNegativeButtonText(getString(R.string.vault_scan_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        biometricPrompt.authenticate(info)
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val STEPS = 3
        private const val MIN_ANSWER = 2
        private const val FAILED_ATTEMPTS_SHOWN = 3
        private const val DOT_FILLED_SCALE = 1.15f

        fun intent(context: Context): Intent = Intent(context, VaultSetupActivity::class.java)
    }
}
