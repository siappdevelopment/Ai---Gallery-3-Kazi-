package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityVaultSettingsBinding
import com.example.thirdgallery.databinding.ItemVaultSettingGroupBinding
import com.example.thirdgallery.databinding.ItemVaultSettingLinkBinding
import com.example.thirdgallery.databinding.ItemVaultSettingToggleBinding
import com.example.thirdgallery.fragment.ConfirmDialogFragment
import com.example.thirdgallery.fragment.VaultAutoLockSheet
import com.example.thirdgallery.helper.VaultSession
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.viewmodel.VaultSettingsViewModel

/**
 * Vault settings (designs 78 and 79): change PIN, fingerprint unlock, auto-lock, recovery question, break-in
 * alerts and Reset vault. Each row works on the real vault setting.
 */
class VaultSettingsActivity : VaultScreenActivity() {

    private var binding: ActivityVaultSettingsBinding? = null
    private val viewModel: VaultSettingsViewModel by viewModels()
    private lateinit var repo: VaultRepository
    private lateinit var biometricPrompt: BiometricPrompt

    private var fingerprintRow: ItemVaultSettingToggleBinding? = null
    private var autoLockRow: ItemVaultSettingLinkBinding? = null
    private var recoveryRow: ItemVaultSettingLinkBinding? = null
    private var alertsCountRow: ItemVaultSettingLinkBinding? = null

    /** Change PIN / recovery question run in the PIN screen; the vault stays open meanwhile. */
    private val setupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            VaultSession.hold(false)
            renderValues()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = VaultRepository(this)

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    repo.setFingerprintEnabled(true)
                    renderValues()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    renderValues()
                }
            },
        )

        val b = ActivityVaultSettingsBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }
        buildRows(b.groups)

        supportFragmentManager.setFragmentResultListener(VaultAutoLockSheet.RESULT_KEY, this) { _, result ->
            val option = result.getInt(VaultAutoLockSheet.RESULT_OPTION)
            repo.autoLock = option
            renderValues()
            Toast.makeText(
                applicationContext,
                getString(R.string.vault_auto_lock_set, getString(VaultAutoLockSheet.OPTIONS[option])),
                Toast.LENGTH_SHORT,
            ).show()
        }
        supportFragmentManager.setFragmentResultListener(KEY_RESET, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) viewModel.resetVault()
        }

        viewModel.alerts.observe(this) { renderValues() }
        viewModel.resetResult.observe(this) { event ->
            event.getContentIfNotHandled()?.let { done ->
                if (done) {
                    // Nothing is left to protect: close every vault screen and go back to the Albums tab.
                    VaultSession.lock()
                    VaultSession.finishScreens()
                    Toast.makeText(applicationContext, R.string.vault_reset_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, R.string.vault_action_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onVaultOpen() = renderValues()

    private fun buildRows(container: LinearLayout) {
        val inflater = layoutInflater

        fun group(title: Int): LinearLayout {
            val g = ItemVaultSettingGroupBinding.inflate(inflater, container, false)
            g.tvGroupTitle.setText(title)
            container.addView(g.root)
            return g.rows
        }

        fun link(rows: LinearLayout, label: Int, danger: Boolean = false, onClick: () -> Unit): ItemVaultSettingLinkBinding {
            val row = ItemVaultSettingLinkBinding.inflate(inflater, rows, false)
            row.tvLabel.setText(label)
            if (danger) row.tvLabel.setTextColor(ContextCompat.getColor(this, R.color.vault_error))
            row.root.setOnClickListener { onClick() }
            rows.addView(row.root)
            return row
        }

        fun toggle(rows: LinearLayout, label: Int, desc: Int): ItemVaultSettingToggleBinding {
            val row = ItemVaultSettingToggleBinding.inflate(inflater, rows, false)
            row.tvLabel.setText(label)
            row.tvDesc.setText(desc)
            rows.addView(row.root)
            return row
        }

        val security = group(R.string.vault_settings_security)
        link(security, R.string.vault_change_pin) { openSetup(VaultSetupActivity.changePinIntent(this)) }
        fingerprintRow = toggle(security, R.string.vault_fingerprint_unlock, R.string.vault_fingerprint_unlock_desc).also { row ->
            row.root.setOnClickListener { row.switchView.performClick() }
            row.switchView.setOnClickListener { onFingerprintToggled(row.switchView.isChecked) }
        }
        autoLockRow = link(security, R.string.vault_auto_lock) { VaultAutoLockSheet.show(supportFragmentManager, repo.autoLock) }
        recoveryRow = link(security, R.string.vault_recovery_question) { openSetup(VaultSetupActivity.recoveryIntent(this)) }

        val alerts = group(R.string.vault_settings_alerts)
        toggle(alerts, R.string.vault_alerts_toggle, R.string.vault_alerts_toggle_desc).also { row ->
            row.switchView.isChecked = repo.alertsEnabled
            row.root.setOnClickListener { row.switchView.performClick() }
            row.switchView.setOnClickListener { repo.alertsEnabled = row.switchView.isChecked }
        }
        alertsCountRow = link(alerts, R.string.vault_alerts_view) { startActivity(VaultAlertsActivity.intent(this)) }

        val vault = group(R.string.vault_settings_vault)
        link(vault, R.string.vault_reset_action, danger = true) { confirmReset() }.also {
            it.tvValue.setText(R.string.vault_reset_row_desc)
        }
    }

    private fun renderValues() {
        fingerprintRow?.switchView?.isChecked = repo.isFingerprintEnabled
        autoLockRow?.tvValue?.setText(VaultAutoLockSheet.OPTIONS[repo.autoLock])
        recoveryRow?.tvValue?.setText(if (repo.hasRecovery) R.string.vault_recovery_set else R.string.vault_recovery_not_set)
        val attempts = viewModel.alerts.value.orEmpty().size
        alertsCountRow?.tvValue?.text = resources.getQuantityString(R.plurals.vault_alerts_count, attempts, attempts)
    }

    private fun openSetup(intent: Intent) {
        VaultSession.hold(true)
        setupLauncher.launch(intent)
    }

    /** Turning fingerprint on asks for the fingerprint once; turning it off is immediate. */
    private fun onFingerprintToggled(on: Boolean) {
        if (!on) {
            repo.setFingerprintEnabled(false)
            return
        }
        val problem = when (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.vault_fp_none_enrolled
            else -> R.string.vault_fp_unavailable
        }
        if (problem != null) {
            Toast.makeText(applicationContext, problem, Toast.LENGTH_SHORT).show()
            renderValues()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.vault_scan_title))
            .setSubtitle(getString(R.string.vault_scan_desc))
            .setNegativeButtonText(getString(R.string.photo_cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        biometricPrompt.authenticate(info)
    }

    private fun confirmReset() {
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_RESET,
            R.drawable.ic_lock,
            getString(R.string.vault_reset_title),
            getString(R.string.vault_reset_body),
            getString(R.string.vault_reset_action),
        )
    }

    override fun onDestroy() {
        binding = null
        fingerprintRow = null
        autoLockRow = null
        recoveryRow = null
        alertsCountRow = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_RESET = "vault_reset"

        fun intent(context: Context): Intent = Intent(context, VaultSettingsActivity::class.java)
    }
}
