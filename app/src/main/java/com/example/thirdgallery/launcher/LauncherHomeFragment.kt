package com.example.thirdgallery.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.DialogLauncherFolderBinding
import com.example.thirdgallery.databinding.FragmentLauncherHomeBinding

/**
 * The home page of the launcher, over the wallpaper: Google search bar with voice search, clock and date, the
 * default-home card, the Google and Tools folders, Calendar and Settings shortcuts, tiles for the gallery and the
 * widgets, and the dock (contacts, phone, messages, browser, camera) resolved from the apps the phone really has.
 */
class LauncherHomeFragment : Fragment(), LauncherSettings.Listener, LauncherApps.Listener {

    private class DockSlot(val view: ImageView, val find: (Context) -> ShortcutTarget?) {
        var target: ShortcutTarget? = null
    }

    private var _binding: FragmentLauncherHomeBinding? = null
    private val binding get() = _binding!!
    private val host get() = activity as? LauncherHost

    private var dock: List<DockSlot> = emptyList()
    private var calendar: ShortcutTarget? = null
    private var googleApps: List<LauncherApp> = emptyList()
    private var toolsApps: List<LauncherApp> = emptyList()
    private var folderDialog: Dialog? = null
    private var lastShortcutAt = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLauncherHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding
        // This page is over the wallpaper, so it keeps clear of the system bars itself.
        ViewCompat.setOnApplyWindowInsetsListener(b.homeRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        dock = listOf(
            DockSlot(b.dockContacts, LauncherShortcuts::contacts),
            DockSlot(b.dockPhone, LauncherShortcuts::dialer),
            DockSlot(b.dockMessages, LauncherShortcuts::sms),
            DockSlot(b.dockBrowser, LauncherShortcuts::browser),
            DockSlot(b.dockCamera, LauncherShortcuts::camera),
        )
        dock.forEach { slot -> slot.view.setOnClickListener { slot.target?.let(::start) } }

        b.llSearch.setOnClickListener { openGoogle() }
        b.ivMic.setOnClickListener { voiceSearch() }
        b.btnSetDefault.setOnClickListener { host?.requestDefaultLauncher() }
        b.cellGoogle.setOnClickListener { showFolder(getString(R.string.launcher_folder_google), googleApps) }
        b.cellTools.setOnClickListener { showFolder(getString(R.string.launcher_folder_tools), toolsApps) }
        b.cellCalendar.setOnClickListener { calendar?.let(::start) }
        b.cellSettings.setOnClickListener { startActivity(LauncherSettingsActivity.intent(requireContext())) }
        b.tileGallery.setOnClickListener { host?.openGallery() }
        b.tileWidgets.setOnClickListener { startActivity(LauncherWidgetsActivity.intent(requireContext())) }
        b.tvSwipeHint.setOnClickListener { host?.openDrawer() }

        LauncherSettings.register(this)
        LauncherApps.register(this)
    }

    override fun onResume() {
        super.onResume()
        // The default phone, messages or browser app may have changed while another app was in front.
        loadShortcuts()
        loadFolders()
        applySettings()
        refreshDefaultPromo()
    }

    override fun onPause() {
        folderDialog?.dismiss()
        super.onPause()
    }

    override fun onDestroyView() {
        LauncherSettings.unregister(this)
        LauncherApps.unregister(this)
        folderDialog?.dismiss()
        folderDialog = null
        _binding = null
        super.onDestroyView()
    }

    override fun onLauncherSettingsChanged() {
        if (_binding != null && isAdded) applySettings()
    }

    override fun onAppsChanged(apps: List<LauncherApp>) {
        if (_binding != null && isAdded) {
            loadFolders()
            applySettings()
        }
    }

    /** Closes a folder pop-up that is open (the Home key, a new intent). */
    fun dismissTransientUi() {
        folderDialog?.dismiss()
    }

    fun refreshDefaultPromo() {
        val b = _binding ?: return
        b.promo.visibility = if (LauncherNav.isDefaultHome(requireContext())) View.GONE else View.VISIBLE
    }

    // ---- data ----

    private fun loadShortcuts() {
        val context = requireContext()
        dock.forEach { it.target = it.find(context) }
        calendar = LauncherShortcuts.calendar(context)
    }

    private fun loadFolders() {
        googleApps = LauncherShortcuts.GOOGLE_PACKAGES.mapNotNull { LauncherApps.find(it) }
        toolsApps = LauncherShortcuts.TOOLS_GROUPS.mapNotNull { group -> group.firstNotNullOfOrNull { LauncherApps.find(it) } }
    }

    // ---- sizes and look ----

    /** Applies icon size, name size and name visibility to everything on the page. */
    private fun applySettings() {
        val b = _binding ?: return
        val context = requireContext()
        val size = LauncherSettings.iconPx(context)
        val labelSp = LauncherSettings.getLabelSize(context).toFloat()
        val labels = LauncherSettings.isLabelVisible(context)
        val corner = size * LauncherIcons.CORNER_RATIO

        fun sizeOf(view: View, px: Int) {
            view.layoutParams = view.layoutParams.apply {
                width = px
                height = px
            }
        }

        // Folders: a glass square with a 3x3 preview.
        bindFolder(b.folderGoogle, b.gridGoogle, googleApps, size, corner)
        bindFolder(b.folderTools, b.gridTools, toolsApps, size, corner)
        b.cellGoogle.visibility = if (googleApps.isEmpty()) View.GONE else View.VISIBLE
        b.cellTools.visibility = if (toolsApps.isEmpty()) View.GONE else View.VISIBLE

        // Calendar: the system app, hidden when the phone has none.
        val cal = calendar
        b.cellCalendar.visibility = if (cal == null) View.GONE else View.VISIBLE
        if (cal != null) {
            sizeOf(b.ivCalendar, size)
            LauncherIcons.bind(b.ivCalendar, cal.icon, size)
            b.tvCalendar.text = cal.label
        }

        // Settings: a glass square with the gear.
        sizeOf(b.ivSettings, size)
        b.ivSettings.background = glass(corner)
        val inset = (size * 0.25f).toInt()
        b.ivSettings.setPadding(inset, inset, inset, inset)

        listOf(b.tvGoogle, b.tvTools, b.tvCalendar, b.tvSettings).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            it.visibility = if (labels) View.VISIBLE else View.GONE
        }

        // Dock: a slot without an app is hidden.
        val dockSide = size + (8 * resources.displayMetrics.density).toInt()
        dock.forEach { slot ->
            val target = slot.target
            slot.view.layoutParams = slot.view.layoutParams.apply { height = dockSide }
            if (target == null) {
                slot.view.visibility = View.GONE
            } else {
                slot.view.visibility = View.VISIBLE
                LauncherIcons.bind(slot.view, target.icon, size)
            }
        }
    }

    private fun glass(corner: Float) = GradientDrawable().apply {
        setColor(0x55FFFFFF)
        cornerRadius = corner
    }

    private fun bindFolder(holder: View, grid: GridLayout, apps: List<LauncherApp>, size: Int, corner: Float) {
        holder.layoutParams = holder.layoutParams.apply {
            width = size
            height = size
        }
        holder.background = glass(corner)
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val pad = maxOf((2 * density).toInt(), size * 4 / 50)
        val margin = density.toInt().coerceAtLeast(1)
        val icon = ((size - 2 * pad - 6 * margin) / FOLDER_COLUMNS).coerceAtLeast((6 * density).toInt())
        grid.setPadding(pad, pad, pad, pad)
        apps.take(FOLDER_MAX_ICONS).forEach { app ->
            val image = ImageView(requireContext())
            image.layoutParams = GridLayout.LayoutParams().apply {
                width = icon
                height = icon
                setMargins(margin, margin, margin, margin)
            }
            LauncherIcons.bind(image, app.icon, icon)
            grid.addView(image)
        }
    }

    // ---- actions ----

    /** Starts a shortcut and remembers it for the Recent page. */
    private fun start(target: ShortcutTarget) {
        val context = context ?: return
        try {
            startActivity(android.content.Intent(target.intent).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            RecentAppsStore.record(context, target.packageName)
        } catch (e: Exception) {
            toast(R.string.launcher_cannot_open)
        }
    }

    /** Debounces taps that open another app, so a double tap cannot start it twice. */
    private fun canLaunchShortcut(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShortcutAt < SHORTCUT_DEBOUNCE_MS) return false
        lastShortcutAt = now
        return true
    }

    private fun openGoogle() {
        if (!canLaunchShortcut()) return
        val context = requireContext()
        val launch = context.packageManager.getLaunchIntentForPackage(LauncherShortcuts.GOOGLE_APP)
        val intent = launch ?: android.content.Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH)
        try {
            startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(R.string.launcher_cannot_open)
        }
    }

    private fun voiceSearch() {
        if (!canLaunchShortcut()) return
        val context = requireContext()
        for (intent in LauncherShortcuts.voiceSearchIntents()) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Try the next one.
            }
        }
        toast(R.string.launcher_cannot_open)
    }

    private fun showFolder(title: String, apps: List<LauncherApp>) {
        if (apps.isEmpty() || folderDialog?.isShowing == true) return
        val context = requireContext()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val d = DialogLauncherFolderBinding.inflate(layoutInflater)
        d.tvTitle.text = title
        val adapter = LauncherAppsAdapter(onClick = { app ->
            dialog.dismiss()
            if (LauncherApps.launch(context, app)) {
                // Back from the app lands on the home page, as for the dock.
            } else {
                toast(R.string.launcher_cannot_open)
            }
        })
        d.rvApps.layoutManager = GridLayoutManager(context, FOLDER_COLUMNS)
        d.rvApps.adapter = adapter
        adapter.applySettings(context)
        adapter.submitList(apps)
        dialog.setContentView(d.root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.setPadding((16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), 0)
        }
        dialog.setOnDismissListener { if (folderDialog === dialog) folderDialog = null }
        folderDialog = dialog
        dialog.show()
    }

    private fun toast(res: Int) {
        context?.applicationContext?.let { Toast.makeText(it, res, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val FOLDER_COLUMNS = 3
        const val FOLDER_MAX_ICONS = 9
        const val SHORTCUT_DEBOUNCE_MS = 800L
    }
}
