package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivitySettingBinding
import com.photovideo.gallerykz.memories.databinding.ItemSettingGroupBinding
import com.photovideo.gallerykz.memories.databinding.ItemSettingRowBinding
import com.photovideo.gallerykz.memories.fragment.ChoiceBottomSheet
import com.photovideo.gallerykz.memories.helper.GalleryLayoutPrefs
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.helper.ThemePrefs
import com.photovideo.gallerykz.memories.helper.VaultSession
import com.photovideo.gallerykz.memories.language.Languages
import com.photovideo.gallerykz.memories.launcher.LauncherHomeActivity
import com.photovideo.gallerykz.memories.launcher.LauncherSettingsActivity
import com.photovideo.gallerykz.memories.launcher.LauncherWidgetsActivity
import com.photovideo.gallerykz.memories.repo.VaultRepository
import com.photovideo.gallerykz.memories.viewmodel.MediaAccess
import com.photovideo.gallerykz.memories.viewmodel.SettingState
import com.photovideo.gallerykz.memories.viewmodel.SettingViewModel
import java.util.Locale

/**
 * Settings (design 49): Appearance, Gallery & memories, Privacy & storage and About. Every row shows the real value of
 * its setting and does what it says: the choices (language, theme, grid columns) are saved and applied at once, the
 * rest opens the screen or the system page where the thing really lives.
 */
class SettingActivity : AppCompatActivity() {

    private class Row(
        @DrawableRes val icon: Int,
        @StringRes val label: Int,
        val value: String?,
        val onClick: (() -> Unit)?,
    )

    private class Group(@StringRes val title: Int, val rows: List<Row>)

    private var binding: ActivitySettingBinding? = null
    private val viewModel: SettingViewModel by viewModels()
    private var lastOpenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivitySettingBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.SETTINGS)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportFragmentManager.setFragmentResultListener(KEY_THEME, this) { _, result ->
            THEMES.getOrNull(result.getInt(ChoiceBottomSheet.RESULT_INDEX, -1))?.let { ThemePrefs.set(this, it) }
        }
        supportFragmentManager.setFragmentResultListener(KEY_LAYOUT, this) { _, result ->
            COLUMN_OPTIONS.getOrNull(result.getInt(ChoiceBottomSheet.RESULT_INDEX, -1))?.let {
                GalleryLayoutPrefs.setColumns(this, it)
                viewModel.refresh()
            }
        }
        viewModel.state.observe(this) { render(it) }
    }

    override fun onStart() {
        super.onStart()
        // A permission, the notifications or the Bin may have changed while another screen was in front.
        viewModel.refresh()
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    // ---- the list ----

    private fun render(state: SettingState) {
        val b = binding ?: return
        b.groups.removeAllViews()
        val inflater = LayoutInflater.from(this)
        groupsOf(state).forEach { group ->
            val g = ItemSettingGroupBinding.inflate(inflater, b.groups, false)
            g.tvGroupTitle.setText(group.title)
            group.rows.forEach { row ->
                val r = ItemSettingRowBinding.inflate(inflater, g.card, false)
                r.ivIcon.setImageResource(row.icon)
                r.tvLabel.setText(row.label)
                r.tvValue.text = row.value.orEmpty()
                r.tvValue.visibility = if (row.value.isNullOrEmpty()) View.GONE else View.VISIBLE
                val click = row.onClick
                r.ivChevron.visibility = if (click != null) View.VISIBLE else View.GONE
                if (click != null) {
                    r.root.setOnClickListener { click() }
                } else {
                    r.root.isClickable = false
                    r.root.background = null
                }
                g.card.addView(r.root)
            }
            b.groups.addView(g.root)
        }
    }

    private fun groupsOf(state: SettingState): List<Group> {
        val onOff = { on: Boolean -> getString(if (on) R.string.settings_on else R.string.settings_off) }
        return listOf(
            Group(
                R.string.settings_group_appearance,
                listOf(
                    Row(R.drawable.ic_translate, R.string.settings_language, languageName(state.languageTag)) { open { LanguageActivity.intent(this, fromSettings = true) } },
                    Row(R.drawable.ic_contrast, R.string.settings_theme, themeLabel(state.themeMode)) { chooseTheme(state) },
                    Row(R.drawable.ic_grid_view, R.string.settings_layout, getString(R.string.settings_columns, state.columns)) { chooseLayout(state) },
                ),
            ),
            Group(
                R.string.settings_group_gallery,
                listOf(
                    Row(R.drawable.ic_face, R.string.home_people_title, null) { open { PeopleAndFaceActivity.intent(this) } },
                    Row(R.drawable.ic_location_on, R.string.home_places_title, null) { open { PlacesActivity.intent(this) } },
                    Row(R.drawable.ic_notifications, R.string.settings_notifications, onOff(state.notificationsOn)) { openNotificationSettings() },
                ),
            ),
            Group(
                R.string.settings_group_privacy,
                listOf(
                    Row(
                        R.drawable.ic_vault,
                        R.string.settings_vault,
                        if (state.vaultSetUp) getString(R.string.settings_on) else getString(R.string.settings_vault_setup),
                    ) { openVault() },
                    Row(R.drawable.ic_delete, R.string.bin_title, state.binCount?.let { getString(R.string.photo_items, it) }) { openBin(state) },
                    Row(R.drawable.ic_lock, R.string.settings_permissions, accessLabel(state.mediaAccess)) { openPermissionSettings() },
                    Row(
                        R.drawable.ic_storage,
                        R.string.settings_storage,
                        state.storageUsedBytes?.let { getString(R.string.settings_storage_used, Formatter.formatFileSize(this, it)) },
                    ) { openStorage() },
                ),
            ),
            Group(
                R.string.settings_group_about,
                listOf(
                    Row(R.drawable.ic_tag, R.string.settings_version, state.versionName) { open { VersionActivity.intent(this) } },
                    Row(R.drawable.ic_share, R.string.settings_share, null) { shareApp() },
                    Row(R.drawable.ic_star_small, R.string.settings_rate, null) { rateApp() },
                ),
            ),
        )
    }

    // ---- values ----

    /** The native name of the language ("Deutsch"), as the language screen shows it. */
    private fun languageName(tag: String?): String {
        if (tag == null) return ""
        val exact = Languages.all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
        val byLanguage = Languages.all.firstOrNull { it.tag.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true) }
        return (exact ?: byLanguage)?.nativeName ?: Locale.forLanguageTag(tag).displayLanguage
    }

    private fun themeLabel(mode: Int): String = getString(
        when (mode) {
            ThemePrefs.LIGHT -> R.string.settings_theme_light
            ThemePrefs.DARK -> R.string.settings_theme_dark
            else -> R.string.settings_theme_light
        },
    )

    private fun accessLabel(access: MediaAccess): String = getString(
        when (access) {
            MediaAccess.ALLOWED -> R.string.settings_allowed
            MediaAccess.LIMITED -> R.string.settings_limited
            MediaAccess.DENIED -> R.string.settings_denied
        },
    )

    // ---- choices ----

    private fun chooseTheme(state: SettingState) {
        ChoiceBottomSheet.show(
            supportFragmentManager, KEY_THEME, getString(R.string.settings_theme),
            listOf(R.string.settings_theme_light, R.string.settings_theme_dark).map { getString(it) },
            listOf(R.string.settings_theme_light_sub, R.string.settings_theme_dark_sub).map { getString(it) },
            THEMES.indexOf(state.themeMode),
        )
    }

    private fun chooseLayout(state: SettingState) {
        val subs = listOf(
            R.string.settings_layout_sub_2, R.string.settings_layout_sub_3, R.string.settings_layout_sub_4, R.string.settings_layout_sub_5,
        ).map { getString(it) }
        ChoiceBottomSheet.show(
            supportFragmentManager, KEY_LAYOUT, getString(R.string.settings_layout),
            COLUMN_OPTIONS.map { getString(R.string.settings_columns, it) }, subs, COLUMN_OPTIONS.indexOf(state.columns),
        )
    }

    // ---- opening things ----

    /** Opens a screen of the app; a second tap while it opens is ignored. */
    private fun open(intent: () -> Intent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(intent())
    }

    /** The vault opens straight away while it is unlocked (auto-lock), otherwise through the PIN screen. */
    private fun openVault() = open {
        val unlocked = VaultSession.isOpen(this) && VaultRepository(this).isSetUp
        if (unlocked) VaultMediaActivity.intent(this) else VaultSetupActivity.intent(this)
    }

    private fun openBin(state: SettingState) {
        if (state.binCount == null) {
            toast(R.string.viewer_unsupported)
            return
        }
        open { TrashActivity.intent(this) }
    }

    private fun openPermissionSettings() {
        if (!MediaPermissionHelper.openAppSettings(this)) toast(R.string.settings_open_failed)
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null))
        }
        startOrToast(intent)
    }

    private fun openStorage() = open { StorageActivity.intent(this) }

    private fun shareApp() {
        val link = "https://play.google.com/store/apps/details?id=$packageName"
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.settings_share_text, getString(R.string.app_name), link))
        startOrToast(Intent.createChooser(send, getString(R.string.settings_share)))
    }

    private fun rateApp() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        if (!startOrNull(market)) {
            startOrToast(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    private fun startOrNull(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun startOrToast(intent: Intent) {
        if (!startOrNull(intent)) toast(R.string.settings_open_failed)
    }

    private fun toast(@StringRes res: Int) = Toast.makeText(applicationContext, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_THEME = "setting_theme"
        private const val KEY_LAYOUT = "setting_layout"
        private const val OPEN_GAP_MS = 600L
        private val THEMES = listOf(ThemePrefs.LIGHT, ThemePrefs.DARK)
        private val COLUMN_OPTIONS = (GalleryLayoutPrefs.MIN_COLUMNS..GalleryLayoutPrefs.MAX_COLUMNS).toList()

        fun intent(context: Context): Intent = Intent(context, SettingActivity::class.java)
    }
}
