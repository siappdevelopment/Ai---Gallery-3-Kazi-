package com.photovideo.gallerykz.memories.launcher

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityLauncherSettingsBinding

/**
 * Launcher settings: choose this app as the Home app, and set the app icon size, the app name size, whether names
 * show and the sort order. Every control saves at once, so the home screen and the open drawer change live.
 */
class LauncherSettingsActivity : AppCompatActivity(), LauncherApps.Listener {

    private var binding: ActivityLauncherSettingsBinding? = null
    private var ignoreChanges = false
    private var roleStartedAt = 0L
    private var roleIsSettings = false

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onRoleResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityLauncherSettingsBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.LAUNCHER_SETTINGS)
        ViewCompat.setOnApplyWindowInsetsListener(b.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.btnWidgets.setOnClickListener { startActivity(LauncherWidgetsActivity.intent(this)) }
        b.btnDefault.setOnClickListener { requestDefault() }

        b.switchLabels.setOnCheckedChangeListener { _, checked ->
            if (!ignoreChanges) LauncherSettings.setLabelVisible(this, checked)
            renderPreview()
        }
        b.seekIcon.setOnSeekBarChangeListener(onProgress { progress ->
            LauncherSettings.setIconSize(this, LauncherSettings.ICON_MIN + progress)
            renderPreview()
        })
        b.seekLabel.setOnSeekBarChangeListener(onProgress { progress ->
            LauncherSettings.setLabelSize(this, LauncherSettings.LABEL_MIN + progress)
            renderPreview()
        })
        b.groupSort.setOnCheckedChangeListener { _, id ->
            if (ignoreChanges) return@setOnCheckedChangeListener
            val sort = when (id) {
                R.id.sortZa -> LauncherSettings.SORT_NAME_DESC
                R.id.sortDate -> LauncherSettings.SORT_INSTALLED
                else -> LauncherSettings.SORT_NAME_ASC
            }
            LauncherSettings.setSort(this, sort)
            LauncherApps.resort(this)
        }

        bindValues()
        LauncherApps.register(this)
        if (LauncherApps.apps.isEmpty()) LauncherApps.reload(this)
    }

    override fun onResume() {
        super.onResume()
        renderDefault()
        renderPreview()
    }

    override fun onDestroy() {
        LauncherApps.unregister(this)
        binding = null
        super.onDestroy()
    }

    override fun onAppsChanged(apps: List<LauncherApp>) = renderPreview()

    private fun bindValues() {
        val b = binding ?: return
        ignoreChanges = true
        b.switchLabels.isChecked = LauncherSettings.isLabelVisible(this)
        b.seekIcon.progress = LauncherSettings.getIconSize(this) - LauncherSettings.ICON_MIN
        b.seekLabel.progress = LauncherSettings.getLabelSize(this) - LauncherSettings.LABEL_MIN
        b.groupSort.check(
            when (LauncherSettings.getSort(this)) {
                LauncherSettings.SORT_NAME_DESC -> R.id.sortZa
                LauncherSettings.SORT_INSTALLED -> R.id.sortDate
                else -> R.id.sortAz
            },
        )
        ignoreChanges = false
    }

    private fun onProgress(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    /** The four sample apps show the chosen sizes; they are the first apps of the real list. */
    private fun renderPreview() {
        val b = binding ?: return
        val iconDp = LauncherSettings.getIconSize(this)
        val labelSp = LauncherSettings.getLabelSize(this)
        val labels = LauncherSettings.isLabelVisible(this)
        b.tvIconSize.text = iconDp.toString()
        b.tvLabelSize.text = labelSp.toString()
        val side = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, iconDp.toFloat(), resources.displayMetrics).toInt()
        val apps = LauncherApps.apps
        val slots = listOf(
            Triple(b.preview1, b.ivPreview1, b.tvPreview1),
            Triple(b.preview2, b.ivPreview2, b.tvPreview2),
            Triple(b.preview3, b.ivPreview3, b.tvPreview3),
            Triple(b.preview4, b.ivPreview4, b.tvPreview4),
        )
        slots.forEachIndexed { index, (root, icon, label) -> bindSlot(root, icon, label, apps.getOrNull(index), side, labelSp, labels) }
    }

    private fun bindSlot(root: View, icon: ImageView, label: TextView, app: LauncherApp?, side: Int, labelSp: Int, labels: Boolean) {
        root.visibility = if (app == null) View.INVISIBLE else View.VISIBLE
        icon.layoutParams = icon.layoutParams.apply {
            width = side
            height = side
        }
        if (app != null) LauncherIcons.bind(icon, app.icon, side) else icon.setImageDrawable(null)
        label.text = app?.label.orEmpty()
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp.toFloat())
        label.visibility = if (labels) View.VISIBLE else View.GONE
    }

    private fun renderDefault() {
        val b = binding ?: return
        val isDefault = LauncherNav.isDefaultHome(this)
        b.tvDefaultSub.setText(if (isDefault) R.string.launcher_default_on else R.string.launcher_default_off)
        b.btnDefault.visibility = if (isDefault) View.GONE else View.VISIBLE
    }

    private fun requestDefault() {
        val intent = LauncherNav.defaultHomeIntent(this)
        if (intent == null) {
            Toast.makeText(applicationContext, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        roleIsSettings = intent.action != "android.app.role.action.REQUEST_ROLE"
        roleStartedAt = SystemClock.elapsedRealtime()
        try {
            roleRequest.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(applicationContext, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onRoleResult() {
        renderDefault()
        if (LauncherNav.isDefaultHome(this) || roleIsSettings) return
        // A role dialog that closes almost at once was skipped by the system: send the user to the Home settings.
        if (SystemClock.elapsedRealtime() - roleStartedAt < SILENT_CANCEL_MS) {
            LauncherNav.homeSettingsIntent(this)?.let {
                roleIsSettings = true
                try {
                    roleRequest.launch(it)
                } catch (e: Exception) {
                    // Nothing more to try.
                }
            }
        }
    }

    companion object {
        private const val SILENT_CANCEL_MS = 800L

        fun intent(context: Context): Intent = Intent(context, LauncherSettingsActivity::class.java)
    }
}
