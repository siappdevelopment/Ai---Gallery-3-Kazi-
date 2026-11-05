package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityVaultSettingsBinding
import com.photovideo.gallerykz.memories.databinding.ItemVaultSettingGroupBinding
import com.photovideo.gallerykz.memories.databinding.ItemVaultSettingLinkBinding
import com.photovideo.gallerykz.memories.databinding.ItemVaultSettingToggleBinding
import com.photovideo.gallerykz.memories.fragment.ConfirmDialogFragment
import com.photovideo.gallerykz.memories.fragment.VaultAutoLockSheet
import com.photovideo.gallerykz.memories.helper.VaultSession
import com.photovideo.gallerykz.memories.repo.VaultRepository
import com.photovideo.gallerykz.memories.viewmodel.VaultSettingsViewModel

/**
 * Vault settings (designs 78 and 79): change PIN, auto-lock, recovery question, break-in
 * alerts and Reset vault. Each row works on the real vault setting.
 */
class VaultSettingsActivity : VaultScreenActivity() {

    private var binding: ActivityVaultSettingsBinding? = null
    private val viewModel: VaultSettingsViewModel by viewModels()
    private lateinit var repo: VaultRepository

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

        val b = ActivityVaultSettingsBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.VAULT_SETTING)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
        autoLockRow?.tvValue?.setText(VaultAutoLockSheet.OPTIONS[repo.autoLock])
        recoveryRow?.tvValue?.setText(if (repo.hasRecovery) R.string.vault_recovery_set else R.string.vault_recovery_not_set)
        val attempts = viewModel.alerts.value.orEmpty().size
        alertsCountRow?.tvValue?.text = resources.getQuantityString(R.plurals.vault_alerts_count, attempts, attempts)
    }

    private fun openSetup(intent: Intent) {
        VaultSession.hold(true)
        setupLauncher.launch(intent)
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
