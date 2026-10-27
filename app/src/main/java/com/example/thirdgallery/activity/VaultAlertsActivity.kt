package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.R
import com.example.thirdgallery.database.VaultAlertEntity
import com.example.thirdgallery.databinding.ActivityVaultAlertsBinding
import com.example.thirdgallery.databinding.ItemVaultAlertBinding
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.viewmodel.VaultSettingsViewModel
import java.util.Date

/** Break-in alerts (design 80): every wrong PIN entered on the vault, newest first, with Clear all. */
class VaultAlertsActivity : VaultScreenActivity() {

    private var binding: ActivityVaultAlertsBinding? = null
    private val viewModel: VaultSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityVaultAlertsBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }
        b.btnClear.setOnClickListener { viewModel.clearAlerts() }
        viewModel.alerts.observe(this) { render(b, it) }
    }

    override fun onVaultOpen() {
        // The alerts switch may have been changed in the settings while this screen was away.
        binding?.offBanner?.visibility = if (VaultRepository(this).alertsEnabled) View.GONE else View.VISIBLE
    }

    private fun render(b: ActivityVaultAlertsBinding, alerts: List<VaultAlertEntity>) {
        val has = alerts.isNotEmpty()
        b.btnClear.visibility = if (has) View.VISIBLE else View.GONE
        b.list.visibility = if (has) View.VISIBLE else View.GONE
        b.emptyGroup.visibility = if (has) View.GONE else View.VISIBLE
        b.offBanner.visibility = if (VaultRepository(this).alertsEnabled) View.GONE else View.VISIBLE
        b.list.removeAllViews()
        alerts.forEach { alert ->
            val row = ItemVaultAlertBinding.inflate(layoutInflater, b.list, false)
            row.tvTime.text = whenText(alert.attemptedAt)
            row.tvAttempt.text = getString(R.string.vault_alerts_attempt, alert.attempt)
            b.list.addView(row.root)
        }
    }

    /** "Today, 8:14 AM", "Yesterday, 11:52 PM", or the full date and time for older ones. */
    private fun whenText(timeMs: Long): String {
        val time = DateFormat.getTimeFormat(this).format(Date(timeMs))
        return when {
            DateUtils.isToday(timeMs) -> getString(R.string.vault_alerts_today, time)
            DateUtils.isToday(timeMs + DateUtils.DAY_IN_MILLIS) -> getString(R.string.vault_alerts_yesterday, time)
            else -> DateUtils.formatDateTime(
                this,
                timeMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, VaultAlertsActivity::class.java)
    }
}
