package com.photovideo.gallerykz.memories.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.ads.AdAwareActivity
import com.photovideo.gallerykz.memories.ads.AdHooks
import com.photovideo.gallerykz.memories.ads.AdScreen
import com.photovideo.gallerykz.memories.database.AppDatabase
import com.photovideo.gallerykz.memories.databinding.ActivityMemoryControlBinding
import com.photovideo.gallerykz.memories.databinding.ItemVaultSettingGroupBinding
import com.photovideo.gallerykz.memories.databinding.ItemVaultSettingLinkBinding
import com.photovideo.gallerykz.memories.databinding.ItemVaultSettingToggleBinding
import com.photovideo.gallerykz.memories.fragment.ConfirmDialogFragment
import com.photovideo.gallerykz.memories.helper.MemoryPrefs
import com.photovideo.gallerykz.memories.notification.MemoryNotificationScheduler
import com.photovideo.gallerykz.memories.repo.FaceScanManager
import com.photovideo.gallerykz.memories.repo.PlaceScanManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Memory controls: six switches (daily memories, sensitive memories, face grouping, location, date-based memories,
 * reminder notifications), shortcuts to People and Places, and "Delete all face data". Every switch is saved at once
 * (see [MemoryPrefs]); the screens that depend on it follow it by themselves.
 */
class MemoryControlActivity : AdAwareActivity() {

    /**
     * One switch. [read] gives the saved state when the row is built. [change] does the work for the wanted state and
     * must call its second argument with the state that really took effect (a refused request keeps the switch off).
     */
    private class Toggle(
        @StringRes val title: Int,
        @StringRes val description: Int,
        val read: (Context) -> Boolean,
        val change: (wanted: Boolean, commit: (Boolean) -> Unit) -> Unit,
    )

    private var navigatedAt = 0L
    private var deleting = false

    /** What to do with the answer of the notification permission dialog (a field: one dialog at a time). */
    private var pendingNotificationCommit: ((Boolean) -> Unit)? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val allowed = granted && NotificationManagerCompat.from(this).areNotificationsEnabled()
            if (!allowed) Toast.makeText(applicationContext, R.string.memory_notifications_denied, Toast.LENGTH_LONG).show()
            pendingNotificationCommit?.invoke(allowed)
            pendingNotificationCommit = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityMemoryControlBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Bottom ad, Back interstitial and click counting for the screens opened from here (the Settings ad block).
        AdHooks.install(this, AdScreen.SETTINGS)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportFragmentManager.setFragmentResultListener(KEY_DELETE, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) deleteFaceData()
        }
        buildRows(b.groups)
    }

    // ---- the list ----

    private fun buildRows(container: LinearLayout) {
        val inflater = layoutInflater

        fun group(@StringRes title: Int): LinearLayout {
            val g = ItemVaultSettingGroupBinding.inflate(inflater, container, false)
            g.tvGroupTitle.setText(title)
            container.addView(g.root)
            // The rows reach the edges of the card and the card clips their touch feedback, so a tap anywhere on the card works.
            g.rows.setPadding(0, 0, 0, 0)
            g.rows.clipToOutline = true
            return g.rows
        }

        fun link(rows: LinearLayout, @StringRes label: Int, @StringRes value: Int? = null, danger: Boolean = false, onClick: () -> Unit) {
            val row = ItemVaultSettingLinkBinding.inflate(inflater, rows, false)
            row.tvLabel.setText(label)
            if (value != null) {
                row.tvValue.setText(value)
                // A long description must not squeeze the name into a narrow column of one letter per line: the name keeps its
                // own width and the description takes the rest, right aligned.
                row.tvLabel.layoutParams = (row.tvLabel.layoutParams as LinearLayout.LayoutParams).apply {
                    width = LinearLayout.LayoutParams.WRAP_CONTENT
                    weight = 0f
                }
                row.tvValue.layoutParams = (row.tvValue.layoutParams as LinearLayout.LayoutParams).apply {
                    width = 0
                    weight = 1f
                }
                row.tvValue.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            } else {
                row.tvValue.visibility = android.view.View.GONE
            }
            if (danger) row.tvLabel.setTextColor(ContextCompat.getColor(this, R.color.vault_error))
            row.root.setOnClickListener { onClick() }
            rows.addView(row.root)
        }

        val toggles = toggles()
        val discovery = group(R.string.memory_section_discovery)
        toggles.forEach { discovery.addView(toggleRow(it, discovery)) }

        val manage = group(R.string.memory_section_manage)
        link(manage, R.string.memory_manage_people) { open(PeopleAndFaceActivity.intent(this)) }
        // A thin line between the two rows, inset like the row text.
        manage.addView(
            android.view.View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.home_doc_line)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (resources.displayMetrics.density).toInt())).apply {
                marginStart = (10 * resources.displayMetrics.density).toInt()
                marginEnd = (10 * resources.displayMetrics.density).toInt()
            },
        )
        link(manage, R.string.memory_manage_places) { open(PlacesActivity.intent(this)) }

        val data = group(R.string.memory_section_data)
        link(data, R.string.memory_delete_title, R.string.memory_delete_row_desc, danger = true) { confirmDeleteFaceData() }
    }

    private fun toggleRow(toggle: Toggle, rows: LinearLayout): android.view.View {
        val row = ItemVaultSettingToggleBinding.inflate(layoutInflater, rows, false)
        row.tvLabel.setText(toggle.title)
        row.tvDesc.setText(toggle.description)
        // The saved state is read once, here; a tap changes the row through commit().
        var on = toggle.read(this)
        row.switchView.isChecked = on
        val commit = { applied: Boolean ->
            on = applied
            row.switchView.isChecked = applied
        }
        row.root.isClickable = true
        row.root.isFocusable = true
        val ripple = android.util.TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) row.root.setBackgroundResource(ripple.resourceId)
        row.root.setOnClickListener { row.switchView.performClick() }
        row.switchView.setOnClickListener {
            // The switch already flipped by itself: take it back until the change says what really happened.
            val wanted = row.switchView.isChecked
            row.switchView.isChecked = on
            toggle.change(wanted, commit)
        }
        return row.root
    }

    private fun toggles(): List<Toggle> = listOf(
        simple(R.string.memory_daily_title, R.string.memory_daily_desc, MemoryPrefs::isDailyMemories, MemoryPrefs::setDailyMemories),
        // Stored only: the app has no sensitive-content classification yet, so nothing reads this switch today.
        simple(R.string.memory_sensitive_title, R.string.memory_sensitive_desc, MemoryPrefs::isSensitiveMemories, MemoryPrefs::setSensitiveMemories),
        simple(R.string.memory_faces_title, R.string.memory_faces_desc, MemoryPrefs::isFaceGrouping, MemoryPrefs::setFaceGrouping) { on ->
            // Turning it off only stops looking: no face is deleted.
            if (on) {
                FaceScanManager.start(applicationContext)
                FaceScanManager.retry(applicationContext)
            }
        },
        simple(R.string.memory_location_title, R.string.memory_location_desc, MemoryPrefs::isLocationMemories, MemoryPrefs::setLocationMemories) { on ->
            // Turning it off only stops reading: the places found so far stay stored. The permission to read the
            // location of photos is asked on the Places screen, not here.
            if (on) {
                PlaceScanManager.start(applicationContext)
                PlaceScanManager.scanNow(applicationContext)
            }
        },
        simple(R.string.memory_dates_title, R.string.memory_dates_desc, MemoryPrefs::isDateMemories, MemoryPrefs::setDateMemories),
        Toggle(R.string.memory_notifications_title, R.string.memory_notifications_desc, MemoryPrefs::isMemoryNotifications, this::changeNotifications),
    )

    /** A switch that is just saved, plus an optional [after] step that runs before the row shows the new state. */
    private fun simple(
        @StringRes title: Int,
        @StringRes description: Int,
        read: (Context) -> Boolean,
        write: (Context, Boolean) -> Unit,
        after: (Boolean) -> Unit = {},
    ) = Toggle(title, description, read) { wanted, commit ->
        write(this, wanted)
        after(wanted)
        commit(wanted)
    }

    // ---- Memory notifications: the only switch that can be refused ----

    private fun changeNotifications(wanted: Boolean, commit: (Boolean) -> Unit) {
        when {
            !wanted -> {
                MemoryPrefs.setMemoryNotifications(this, false)
                MemoryNotificationScheduler.cancel(this)
                commit(false)
            }
            notificationsAllowed() -> enableNotifications(commit)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostPermission() -> {
                // The row turns on only after a real grant.
                pendingNotificationCommit = { granted -> if (granted) enableNotifications(commit) else commit(false) }
                try {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                    pendingNotificationCommit = null
                    commit(false)
                }
            }
            else -> {
                // Notifications are blocked for the whole app in the system settings.
                Toast.makeText(applicationContext, R.string.memory_notifications_denied, Toast.LENGTH_LONG).show()
                commit(false)
            }
        }
    }

    private fun enableNotifications(commit: (Boolean) -> Unit) {
        MemoryPrefs.setMemoryNotifications(this, true)
        MemoryNotificationScheduler.schedule(this)
        commit(true)
    }

    private fun hasPostPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPostPermission()) &&
            NotificationManagerCompat.from(this).areNotificationsEnabled()

    // ---- Manage and Delete ----

    /** Opens People or Places; a second tap while it opens is ignored. */
    private fun open(intent: Intent) {
        val now = SystemClock.elapsedRealtime()
        if (isFinishing || now - navigatedAt < OPEN_GAP_MS) return
        navigatedAt = now
        startActivity(intent)
    }

    private fun confirmDeleteFaceData() {
        if (deleting || isFinishing) return
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_DELETE,
            R.drawable.ic_delete_forever,
            getString(R.string.memory_delete_title),
            getString(R.string.memory_delete_message),
            getString(R.string.memory_delete_confirm),
        )
    }

    /**
     * Deletes every face, scanned-photo mark and name. Photos, places and the Face grouping switch are untouched, and
     * no scan is started: with the switch on, faces come back when a scan next runs.
     */
    private fun deleteFaceData() {
        if (deleting) return
        deleting = true
        val app = applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppDatabase.getInstance(app).faceDao().deleteAllFaceData() }.isSuccess
            }
            deleting = false
            // The application context, so the message survives the screen closing.
            Toast.makeText(app, if (ok) R.string.memory_delete_done else R.string.memory_delete_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val KEY_DELETE = "memory_delete_faces"
        private const val OPEN_GAP_MS = 600L

        fun intent(context: Context): Intent =
            Intent(context, MemoryControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
